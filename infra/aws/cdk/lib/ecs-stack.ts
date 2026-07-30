// [TASK: ATOM-INFRA-506]
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecsPatterns from 'aws-cdk-lib/aws-ecs-patterns';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import { Construct } from 'constructs';

export interface EcsSecretRefs {
  readonly jwtSecret: secretsmanager.ISecret;
  readonly dbPassword: secretsmanager.ISecret;
  readonly twilioAuthToken: secretsmanager.ISecret;
  readonly anthropicApiKey: secretsmanager.ISecret;
  readonly slackWebhookUrl: secretsmanager.ISecret;
}

export interface EcsStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly dbInstance: rds.IDatabaseInstance;
  readonly dbSecurityGroup: ec2.ISecurityGroup;
  readonly redisEndpoint: string;
  readonly redisPort: string;
  readonly redisSecurityGroup: ec2.ISecurityGroup;
  readonly mskSecurityGroup: ec2.ISecurityGroup;
  readonly mskClusterArn: string;
  readonly secrets: EcsSecretRefs;
}

/**
 * ECS Fargate cluster hosting all four services: the Spring Boot API (behind an
 * Application Load Balancer) plus notification-service, audit-service, and
 * Debezium as internal workers. Every credential is injected via `secrets:`
 * (valueFrom Secrets Manager ARN) — never as an environment literal. Data-store
 * security groups are opened to the task security groups only.
 *
 * HTTPS: pass `-c certificateArn=arn:aws:acm:...` to attach an ACM cert and a
 * 443 listener; absent that, the ALB serves HTTP :80 for bootstrap/synth.
 */
export class EcsStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: EcsStackProps) {
    super(scope, id, props);

    const cluster = new ecs.Cluster(this, 'SchedulerCluster', {
      vpc: props.vpc,
      clusterName: 'scheduler',
      containerInsights: true,
    });

    // One ECR repo per built service (CI pushes images here — ATOM-INFRA-507).
    const apiRepo = new ecr.Repository(this, 'ApiRepo', { repositoryName: 'scheduler-api' });
    const notificationRepo = new ecr.Repository(this, 'NotificationRepo', {
      repositoryName: 'scheduler-notification',
    });
    const auditRepo = new ecr.Repository(this, 'AuditRepo', { repositoryName: 'scheduler-audit' });

    const logGroup = new logs.LogGroup(this, 'SchedulerLogs', {
      logGroupName: '/ecs/scheduler',
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const ecsSecrets: Record<string, ecs.Secret> = {
      JWT_SECRET: ecs.Secret.fromSecretsManager(props.secrets.jwtSecret),
      DB_PASSWORD: ecs.Secret.fromSecretsManager(props.secrets.dbPassword),
      TWILIO_AUTH_TOKEN: ecs.Secret.fromSecretsManager(props.secrets.twilioAuthToken),
      ANTHROPIC_API_KEY: ecs.Secret.fromSecretsManager(props.secrets.anthropicApiKey),
    };

    const commonEnv: Record<string, string> = {
      DB_URL: `jdbc:postgresql://${props.dbInstance.dbInstanceEndpointAddress}:5432/scheduler`,
      DB_USER: 'scheduler',
      REDIS_HOST: props.redisEndpoint,
      REDIS_PORT: props.redisPort,
      CORS_ALLOWED_ORIGINS: this.node.tryGetContext('corsAllowedOrigins') ?? '',
    };

    // ---- API service (ALB-fronted) --------------------------------------
    const certificateArn = this.node.tryGetContext('certificateArn') as string | undefined;
    const certificate = certificateArn
      ? acm.Certificate.fromCertificateArn(this, 'ApiCert', certificateArn)
      : undefined;

    const api = new ecsPatterns.ApplicationLoadBalancedFargateService(this, 'ApiService', {
      cluster,
      serviceName: 'scheduler-api',
      cpu: 1024,
      memoryLimitMiB: 2048,
      desiredCount: 2,
      publicLoadBalancer: true,
      certificate,
      redirectHTTP: certificate !== undefined,
      taskImageOptions: {
        image: ecs.ContainerImage.fromEcrRepository(apiRepo, 'latest'),
        containerPort: 8080,
        environment: commonEnv,
        secrets: ecsSecrets,
        logDriver: ecs.LogDrivers.awsLogs({ streamPrefix: 'api', logGroup }),
      },
    });
    api.targetGroup.configureHealthCheck({
      path: '/actuator/health',
      healthyHttpCodes: '200',
    });

    // ---- internal workers ----------------------------------------------
    const workerEnv: Record<string, string> = {
      DB_URL: commonEnv.DB_URL,
      DB_USER: commonEnv.DB_USER,
      KAFKA_BOOTSTRAP: cdk.Fn.importValue('scheduler-msk:BootstrapBrokersTls'),
    };

    this.makeWorker('NotificationService', cluster, notificationRepo, logGroup, 'notification',
      { ...workerEnv }, { TWILIO_AUTH_TOKEN: ecsSecrets.TWILIO_AUTH_TOKEN }, 2, 512, 1024);
    this.makeWorker('AuditService', cluster, auditRepo, logGroup, 'audit',
      { ...workerEnv }, {}, 2, 512, 1024);

    // Debezium runs from the public image and relays the outbox to MSK.
    const debeziumTask = new ecs.FargateTaskDefinition(this, 'DebeziumTask', {
      cpu: 512,
      memoryLimitMiB: 1024,
    });
    debeziumTask.addContainer('debezium', {
      image: ecs.ContainerImage.fromRegistry('debezium/connect:2.6'),
      environment: {
        GROUP_ID: 'scheduler-debezium',
        CONFIG_STORAGE_TOPIC: 'debezium_configs',
        OFFSET_STORAGE_TOPIC: 'debezium_offsets',
        STATUS_STORAGE_TOPIC: 'debezium_statuses',
      },
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'debezium', logGroup }),
    });
    const debeziumService = new ecs.FargateService(this, 'DebeziumService', {
      cluster,
      serviceName: 'scheduler-debezium',
      taskDefinition: debeziumTask,
      desiredCount: 1,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
    });

    // ---- data-store ingress (task SGs only) -----------------------------
    const clients: ec2.IConnectable[] = [api.service, debeziumService];
    for (const c of clients) {
      props.dbSecurityGroup.addIngressRule(
        c.connections.securityGroups[0], ec2.Port.tcp(5432), 'API/worker -> RDS');
      props.redisSecurityGroup.addIngressRule(
        c.connections.securityGroups[0], ec2.Port.tcp(6379), 'API/worker -> Redis');
      props.mskSecurityGroup.addIngressRule(
        c.connections.securityGroups[0], ec2.Port.tcp(9094), 'API/worker -> MSK (TLS)');
    }

    new cdk.CfnOutput(this, 'ApiLoadBalancerDns', {
      value: api.loadBalancer.loadBalancerDnsName,
    });
    new cdk.CfnOutput(this, 'ClusterName', { value: cluster.clusterName });
  }

  private makeWorker(
    id: string,
    cluster: ecs.ICluster,
    repo: ecr.IRepository,
    logGroup: logs.ILogGroup,
    streamPrefix: string,
    environment: Record<string, string>,
    secrets: Record<string, ecs.Secret>,
    desiredCount: number,
    cpu: number,
    memoryLimitMiB: number,
  ): ecs.FargateService {
    const taskDef = new ecs.FargateTaskDefinition(this, `${id}Task`, { cpu, memoryLimitMiB });
    taskDef.addContainer(`${id}Container`, {
      image: ecs.ContainerImage.fromEcrRepository(repo, 'latest'),
      environment,
      secrets,
      logging: ecs.LogDrivers.awsLogs({ streamPrefix, logGroup }),
    });
    return new ecs.FargateService(this, id, {
      cluster,
      serviceName: `scheduler-${streamPrefix}`,
      taskDefinition: taskDef,
      desiredCount,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
    });
  }
}
