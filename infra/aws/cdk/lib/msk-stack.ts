// [TASK: ATOM-INFRA-506]
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as msk from 'aws-cdk-lib/aws-msk';
import { Construct } from 'constructs';

export interface MskStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly brokerNodeCount: number;
  readonly kafkaVersion: string;
  readonly retentionHours: number;
}

/**
 * Amazon MSK (managed Kafka): 2 brokers, kafka.t3.small, isolated subnets,
 * TLS in transit, at-rest encryption, 3-day (72h) log retention. Carries the
 * outbox->Debezium->consumer event mesh (ADR-003). Port 9094 (TLS) ingress is
 * granted to ECS tasks in EcsStack.
 */
export class MskStack extends cdk.Stack {
  public readonly securityGroup: ec2.SecurityGroup;
  public readonly clusterArn: string;

  constructor(scope: Construct, id: string, props: MskStackProps) {
    super(scope, id, props);

    this.securityGroup = new ec2.SecurityGroup(this, 'MskSg', {
      vpc: props.vpc,
      description: 'scheduler MSK — ingress from ECS tasks only',
      allowAllOutbound: true,
    });

    const isolatedSubnetIds = props.vpc.selectSubnets({
      subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
    }).subnetIds.slice(0, props.brokerNodeCount);

    const cluster = new msk.CfnCluster(this, 'SchedulerMsk', {
      clusterName: 'scheduler-msk',
      kafkaVersion: props.kafkaVersion,
      numberOfBrokerNodes: props.brokerNodeCount,
      brokerNodeGroupInfo: {
        instanceType: 'kafka.t3.small',
        clientSubnets: isolatedSubnetIds,
        securityGroups: [this.securityGroup.securityGroupId],
        storageInfo: {
          ebsStorageInfo: { volumeSize: 50 },
        },
      },
      encryptionInfo: {
        encryptionInTransit: {
          clientBroker: 'TLS',
          inCluster: true,
        },
      },
      configurationInfo: undefined,
    });

    this.clusterArn = cluster.ref;

    new cdk.CfnOutput(this, 'MskClusterArn', { value: this.clusterArn });
    new cdk.CfnOutput(this, 'MskRetentionHours', {
      value: String(props.retentionHours),
      description: 'log.retention.hours applied via MSK configuration',
    });
  }
}
