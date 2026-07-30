// [TASK: ATOM-INFRA-506]
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';

export interface RdsStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly dbPasswordSecret: secretsmanager.ISecret;
  readonly instanceType: ec2.InstanceType;
  readonly multiAz: boolean;
  readonly backupRetentionDays: number;
}

/**
 * RDS PostgreSQL 15, Multi-AZ, in the isolated subnet tier. Automated daily
 * backups (7-day retention), encrypted storage, master password from Secrets
 * Manager. Ingress on 5432 is granted to the ECS task security group in
 * EcsStack; nothing is publicly accessible.
 */
export class RdsStack extends cdk.Stack {
  public readonly instance: rds.DatabaseInstance;
  public readonly securityGroup: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: RdsStackProps) {
    super(scope, id, props);

    this.securityGroup = new ec2.SecurityGroup(this, 'RdsSg', {
      vpc: props.vpc,
      description: 'scheduler RDS PostgreSQL — ingress from ECS tasks only',
      allowAllOutbound: false,
    });

    this.instance = new rds.DatabaseInstance(this, 'SchedulerDb', {
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_15,
      }),
      instanceType: props.instanceType,
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [this.securityGroup],
      multiAz: props.multiAz,
      allocatedStorage: 50,
      maxAllocatedStorage: 200,
      storageEncrypted: true,
      databaseName: 'scheduler',
      credentials: rds.Credentials.fromSecret(props.dbPasswordSecret),
      backupRetention: cdk.Duration.days(props.backupRetentionDays),
      deletionProtection: true,
      removalPolicy: cdk.RemovalPolicy.SNAPSHOT,
      cloudwatchLogsExports: ['postgresql'],
    });

    new cdk.CfnOutput(this, 'DbEndpoint', {
      value: this.instance.dbInstanceEndpointAddress,
    });
  }
}
