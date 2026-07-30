// [TASK: ATOM-INFRA-506]
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as elasticache from 'aws-cdk-lib/aws-elasticache';
import { Construct } from 'constructs';

export interface ElastiCacheStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly nodeType: string;
}

/**
 * ElastiCache Redis 7 (single node initially; cluster-mode is the scale path).
 * Isolated subnets, in-transit + at-rest encryption. Used for the tenant-scoped
 * slot cache (ATOM-SLOT-008), OTP + API rate-limit counters, and short-TTL
 * result caching. Port 6379 ingress granted to ECS tasks in EcsStack.
 */
export class ElastiCacheStack extends cdk.Stack {
  public readonly securityGroup: ec2.SecurityGroup;
  public readonly primaryEndpointAddress: string;
  public readonly port: string;

  constructor(scope: Construct, id: string, props: ElastiCacheStackProps) {
    super(scope, id, props);

    this.securityGroup = new ec2.SecurityGroup(this, 'RedisSg', {
      vpc: props.vpc,
      description: 'scheduler Redis — ingress from ECS tasks only',
      allowAllOutbound: false,
    });

    const subnetGroup = new elasticache.CfnSubnetGroup(this, 'RedisSubnetGroup', {
      description: 'scheduler Redis isolated subnets',
      subnetIds: props.vpc.selectSubnets({
        subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
      }).subnetIds,
      cacheSubnetGroupName: 'scheduler-redis-subnets',
    });

    const redis = new elasticache.CfnReplicationGroup(this, 'SchedulerRedis', {
      replicationGroupDescription: 'scheduler slot cache + rate limiters',
      engine: 'redis',
      engineVersion: '7.1',
      cacheNodeType: props.nodeType,
      numCacheClusters: 1,
      automaticFailoverEnabled: false,
      cacheSubnetGroupName: subnetGroup.ref,
      securityGroupIds: [this.securityGroup.securityGroupId],
      atRestEncryptionEnabled: true,
      transitEncryptionEnabled: true,
      port: 6379,
    });
    redis.addDependency(subnetGroup);

    this.primaryEndpointAddress = redis.attrPrimaryEndPointAddress;
    this.port = redis.attrPrimaryEndPointPort;

    new cdk.CfnOutput(this, 'RedisEndpoint', { value: this.primaryEndpointAddress });
  }
}
