#!/usr/bin/env node
// [TASK: ATOM-INFRA-506]
// CDK app entry point. Six stacks composed via an explicit dependency graph:
//   VpcStack, SecretsStack -> RdsStack, ElastiCacheStack, MskStack -> EcsStack
//
// No secret values live here or in any stack — SecretsStack creates empty
// Secrets Manager entries (populated out-of-band, see infra/aws/README.md) and
// EcsStack injects them into task definitions via `secrets:` (valueFrom).
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import { VpcStack } from '../lib/vpc-stack';
import { SecretsStack } from '../lib/secrets-stack';
import { RdsStack } from '../lib/rds-stack';
import { ElastiCacheStack } from '../lib/elasticache-stack';
import { MskStack } from '../lib/msk-stack';
import { EcsStack } from '../lib/ecs-stack';

const app = new cdk.App();

const env: cdk.Environment = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'us-east-1',
};

// Generic, industry-neutral naming throughout (scheduler-*).
const prefix = 'scheduler';
const commonTags = { project: 'scheduler', managedBy: 'cdk', environment: 'production' };
const tag = (stack: cdk.Stack) =>
  Object.entries(commonTags).forEach(([k, v]) => cdk.Tags.of(stack).add(k, v));

const vpc = new VpcStack(app, `${prefix}-vpc`, {
  env,
  maxAzs: 2,
  natGateways: 1,
});
tag(vpc);

const secrets = new SecretsStack(app, `${prefix}-secrets`, { env });
tag(secrets);

const rds = new RdsStack(app, `${prefix}-rds`, {
  env,
  vpc: vpc.vpc,
  dbPasswordSecret: secrets.dbPassword,
  instanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MEDIUM),
  multiAz: true,
  backupRetentionDays: 7,
});
rds.addDependency(vpc);
rds.addDependency(secrets);
tag(rds);

const cache = new ElastiCacheStack(app, `${prefix}-elasticache`, {
  env,
  vpc: vpc.vpc,
  nodeType: 'cache.t3.micro',
});
cache.addDependency(vpc);
tag(cache);

const msk = new MskStack(app, `${prefix}-msk`, {
  env,
  vpc: vpc.vpc,
  brokerNodeCount: 2,
  kafkaVersion: '3.5.1',
  retentionHours: 72,
});
msk.addDependency(vpc);
tag(msk);

const ecs = new EcsStack(app, `${prefix}-ecs`, {
  env,
  vpc: vpc.vpc,
  dbInstance: rds.instance,
  dbSecurityGroup: rds.securityGroup,
  redisEndpoint: cache.primaryEndpointAddress,
  redisPort: cache.port,
  redisSecurityGroup: cache.securityGroup,
  mskSecurityGroup: msk.securityGroup,
  mskClusterArn: msk.clusterArn,
  secrets: {
    jwtSecret: secrets.jwtSecret,
    dbPassword: secrets.dbPassword,
    twilioAuthToken: secrets.twilioAuthToken,
    anthropicApiKey: secrets.anthropicApiKey,
    slackWebhookUrl: secrets.slackWebhookUrl,
  },
});
ecs.addDependency(rds);
ecs.addDependency(cache);
ecs.addDependency(msk);
ecs.addDependency(secrets);
tag(ecs);

app.synth();
