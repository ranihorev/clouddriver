/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing
import com.amazonaws.services.elasticloadbalancing.model.*
import com.amazonaws.services.shield.AWSShield
import com.amazonaws.services.shield.model.CreateProtectionRequest
import com.netflix.spinnaker.clouddriver.aws.TestCredential
import com.netflix.spinnaker.clouddriver.aws.deploy.description.UpsertAmazonLoadBalancerClassicDescription
import com.netflix.spinnaker.clouddriver.aws.model.SubnetAnalyzer
import com.netflix.spinnaker.clouddriver.aws.model.SubnetTarget
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider
import com.netflix.spinnaker.clouddriver.aws.services.RegionScopedProviderFactory
import com.netflix.spinnaker.clouddriver.aws.services.SecurityGroupService
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperationException
import com.netflix.spinnaker.config.AwsConfiguration
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class UpsertAmazonLoadBalancerClassicAtomicOperationSpec extends Specification {

  def setupSpec() {
    TaskRepository.threadLocalTask.set(Mock(Task))
  }

  UpsertAmazonLoadBalancerClassicDescription description = new UpsertAmazonLoadBalancerClassicDescription(
          name: "kato-main-frontend",
          availabilityZones: ["us-east-1": ["us-east-1a"]],
          listeners: [
                  new UpsertAmazonLoadBalancerClassicDescription.Listener(
                          externalProtocol: UpsertAmazonLoadBalancerClassicDescription.Listener.ListenerType.HTTP,
                          externalPort: 80,
                          internalPort: 8501
                  )
          ],
          securityGroups: ["foo"],
          credentials: TestCredential.named('bar'),
          healthCheck: "HTTP:7001/health",
          healthCheckPort: 7001
  )
  AmazonElasticLoadBalancing loadBalancing = Mock(AmazonElasticLoadBalancing)
  AWSShield awsShield = Mock(AWSShield)
  def mockAmazonClientProvider = Stub(AmazonClientProvider) {
    getAmazonElasticLoadBalancing(_, _, true) >> loadBalancing
    getAmazonShield(_, _) >> awsShield
  }
  def mockSecurityGroupService = Stub(SecurityGroupService) {
    getSecurityGroupIds(["foo"], null) >> ["foo": "sg-1234"]
  }
  def mockSubnetAnalyzer = Mock(SubnetAnalyzer)
  def regionScopedProvider = Stub(RegionScopedProviderFactory.RegionScopedProvider) {
    getSecurityGroupService() >> mockSecurityGroupService
    getSubnetAnalyzer() >> mockSubnetAnalyzer
  }
  def regionScopedProviderFactory = Stub(RegionScopedProviderFactory) {
    forRegion(_, "us-east-1") >> regionScopedProvider
  }

  def ingressLoadBalancerBuilder = Mock(IngressLoadBalancerBuilder)

  @Subject operation = new UpsertAmazonLoadBalancerAtomicOperation(description)

  def setup() {
    operation.deployDefaults = new AwsConfiguration.DeployDefaults(addAppGroupToServerGroup: true, createLoadBalancerIngressPermissions: true)
    operation.amazonClientProvider = mockAmazonClientProvider
    operation.regionScopedProviderFactory = regionScopedProviderFactory
    operation.ingressLoadBalancerBuilder = ingressLoadBalancerBuilder
  }

  void "should create load balancer"() {
    given:
    def existingLoadBalancers = []
    description.vpcId = "vpcId"

    when:
    description.subnetType = 'internal'
    operation.operate([])

    then:
    1 * ingressLoadBalancerBuilder.ingressApplicationLoadBalancerGroup(
      'kato',
      'us-east-1',
      'bar',
      description.credentials,
      "vpcId",
      { it.toList().sort() == [7001, 8501] },
      _) >> new IngressLoadBalancerBuilder.IngressLoadBalancerGroupResult("sg-1234", "kato-elb")

    and:
    1 * mockSubnetAnalyzer.getSubnetIdsForZones(['us-east-1a'], 'internal', SubnetTarget.ELB, 1) >> ["subnet-1"]
    1 * loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])) >>
            new DescribeLoadBalancersResult(loadBalancerDescriptions: existingLoadBalancers)
    1 * loadBalancing.createLoadBalancer(new CreateLoadBalancerRequest(
            loadBalancerName: "kato-main-frontend",
            listeners: [
                    new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8501)
            ],
            availabilityZones: [],
            subnets: ["subnet-1"],
            securityGroups: ["sg-1234"],
            scheme: "internal",
            tags: []
    )) >> new CreateLoadBalancerResult(dNSName: "dnsName1")
    1 * loadBalancing.configureHealthCheck(new ConfigureHealthCheckRequest(
            loadBalancerName: "kato-main-frontend",
            healthCheck: new HealthCheck(
                    target: "HTTP:7001/health",
                    interval: 10,
                    timeout: 5,
                    unhealthyThreshold: 2,
                    healthyThreshold: 10
            )
    ))
    1 * loadBalancing.modifyLoadBalancerAttributes(new ModifyLoadBalancerAttributesRequest(
            loadBalancerName: "kato-main-frontend",
            loadBalancerAttributes: new LoadBalancerAttributes(
                    crossZoneLoadBalancing: new CrossZoneLoadBalancing(enabled: true),
                    connectionDraining: new ConnectionDraining(enabled: false),
                    additionalAttributes: [],
                    connectionSettings:  new ConnectionSettings(idleTimeout: 60)
            )
    ))
    0 * _
  }

  void "should fail updating a load balancer with no security groups in VPC"() {
    given:
    def existingLoadBalancers = [
      new LoadBalancerDescription(loadBalancerName: "kato-main-frontend", vPCId: "test-vpc").withListenerDescriptions(
        new ListenerDescription().withListener(new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8501))
      )
    ]

    and:
    loadBalancing.describeLoadBalancers(
      new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])
    ) >> new DescribeLoadBalancersResult(loadBalancerDescriptions: existingLoadBalancers)

    and: 'auto-creating groups fails'
    description.securityGroups = []
    description.vpcId = "vpcId"

    when:
    operation.operate([])

    then:
    thrown(IllegalArgumentException)

    when: "in EC2 classic"
    existingLoadBalancers = [
      new LoadBalancerDescription(loadBalancerName: "kato-main-frontend").withListenerDescriptions(
        new ListenerDescription().withListener(new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8501))
      )
    ]

    and:
    loadBalancing.describeLoadBalancers(
      new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])
    ) >> new DescribeLoadBalancersResult(loadBalancerDescriptions: existingLoadBalancers)

    then:
    notThrown(IllegalArgumentException)
  }

  void "should update existing load balancer"() {
    def existingLoadBalancers = [
      new LoadBalancerDescription(loadBalancerName: "kato-main-frontend")
        .withListenerDescriptions(
        new ListenerDescription().withListener(new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8501))
      )
    ]

    given:
    description.listeners.add(
      new UpsertAmazonLoadBalancerClassicDescription.Listener(
        externalProtocol: UpsertAmazonLoadBalancerClassicDescription.Listener.ListenerType.HTTP,
        externalPort: 8080,
        internalPort: 8080
      ))
    description.crossZoneBalancing = true

    when:
    operation.operate([])

    then:
    1 * loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])) >>
            new DescribeLoadBalancersResult(loadBalancerDescriptions: existingLoadBalancers)
    1 * loadBalancing.createLoadBalancerListeners(new CreateLoadBalancerListenersRequest(
            loadBalancerName: "kato-main-frontend",
            listeners: [ new Listener(protocol: "HTTP", loadBalancerPort: 8080, instanceProtocol: "HTTP", instancePort: 8080) ]
    ))
    1 * loadBalancing.applySecurityGroupsToLoadBalancer(new ApplySecurityGroupsToLoadBalancerRequest(
            loadBalancerName: "kato-main-frontend",
            securityGroups: ["sg-1234"]
    ))
    1 * loadBalancing.configureHealthCheck(new ConfigureHealthCheckRequest(
            loadBalancerName: "kato-main-frontend",
            healthCheck: new HealthCheck(
                    target: "HTTP:7001/health",
                    interval: 10,
                    timeout: 5,
                    unhealthyThreshold: 2,
                    healthyThreshold: 10
            )
    ))
    1 * loadBalancing.describeLoadBalancerAttributes(new DescribeLoadBalancerAttributesRequest(loadBalancerName: "kato-main-frontend")) >>
            new DescribeLoadBalancerAttributesResult(loadBalancerAttributes:
              new LoadBalancerAttributes(
                crossZoneLoadBalancing: new CrossZoneLoadBalancing(enabled: false),
                connectionDraining: new ConnectionDraining(enabled: false)))
    1 * loadBalancing.modifyLoadBalancerAttributes(new ModifyLoadBalancerAttributesRequest(
            loadBalancerName: "kato-main-frontend",
            loadBalancerAttributes: new LoadBalancerAttributes(
                    crossZoneLoadBalancing: new CrossZoneLoadBalancing(enabled: true),
                    connectionSettings:  new ConnectionSettings(idleTimeout: 60),
                    additionalAttributes: []
            )
    ))
    0 * _
  }

  @Unroll
  void "should use existing loadbalancer attributes to #desc if not explicitly provided in description"() {
    def existingLoadBalancers = [
      new LoadBalancerDescription(loadBalancerName: "kato-main-frontend")
        .withListenerDescriptions(
        new ListenerDescription().withListener(new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8501))
      )
    ]

    given:
    description.crossZoneBalancing = descriptionCrossZone
    description.connectionDraining = descriptionDraining
    description.deregistrationDelay = descriptionTimeout

    when:
    operation.operate([])

    then:
    1 * loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])) >>
      new DescribeLoadBalancersResult(loadBalancerDescriptions: existingLoadBalancers)
    1 * loadBalancing.applySecurityGroupsToLoadBalancer(new ApplySecurityGroupsToLoadBalancerRequest(
      loadBalancerName: "kato-main-frontend",
      securityGroups: ["sg-1234"]
    ))
    1 * loadBalancing.configureHealthCheck(new ConfigureHealthCheckRequest(
      loadBalancerName: "kato-main-frontend",
      healthCheck: new HealthCheck(
        target: "HTTP:7001/health",
        interval: 10,
        timeout: 5,
        unhealthyThreshold: 2,
        healthyThreshold: 10
      )
    ))
    1 * loadBalancing.describeLoadBalancerAttributes(new DescribeLoadBalancerAttributesRequest(loadBalancerName: "kato-main-frontend")) >>
      new DescribeLoadBalancerAttributesResult(loadBalancerAttributes:
        new LoadBalancerAttributes(
          crossZoneLoadBalancing: new CrossZoneLoadBalancing(enabled: existingCrossZone),
          connectionDraining: new ConnectionDraining(enabled: existingDraining, timeout: existingTimeout),
          connectionSettings: new ConnectionSettings(idleTimeout: existingIdleTimeout)))
    expectedInv * loadBalancing.modifyLoadBalancerAttributes(new ModifyLoadBalancerAttributesRequest(
      loadBalancerName: "kato-main-frontend",
      loadBalancerAttributes: expectedAttributes))
    0 * _


    where:
    desc                  | expectedInv | existingCrossZone | descriptionCrossZone | existingDraining | existingTimeout | descriptionDraining | descriptionTimeout | existingIdleTimeout | descriptionIdleTimeout
    "make no changes"     | 0           | true              | null                 | true             | 300             | null                | null               | 60                  | 60
    "enable cross zone"   | 1           | false             | true                 | true             | 123             | null                | null               | 60                  | 60
    "enable draining"     | 1           | true              | null                 | false            | 300             | true                | null               | 60                  | 60
    "modify timeout"      | 1           | true              | null                 | false            | 300             | null                | 150                | 60                  | 60
    "modify idle timeout" | 0           | true              | null                 | true             | 300             | null                | null               | 60                  | 120

    expectedAttributes = expectedAttributes(existingCrossZone, descriptionCrossZone, existingDraining, existingTimeout, descriptionDraining, descriptionTimeout, existingIdleTimeout, descriptionIdleTimeout)
  }

  private LoadBalancerAttributes expectedAttributes(existingCrossZone, descriptionCrossZone, existingDraining, existingTimeout, descriptionDraining, descriptionTimeout, existingIdleTimeout, descriptionIdleTimeout) {
    CrossZoneLoadBalancing czlb = null
    if (existingCrossZone != descriptionCrossZone && descriptionCrossZone != null) {
      czlb = new CrossZoneLoadBalancing(enabled:  descriptionCrossZone)
    }
    ConnectionSettings cs = null
    if (existingIdleTimeout != descriptionIdleTimeout) {
      cs = new ConnectionSettings(idleTimeout: descriptionIdleTimeout)
    }
    ConnectionDraining cd = null
    if ((descriptionDraining != null || descriptionTimeout != null) && (existingDraining != descriptionDraining || existingTimeout != descriptionTimeout)) {
      cd = new ConnectionDraining(enabled: [descriptionDraining, existingDraining].findResult(Closure.IDENTITY), timeout: [descriptionTimeout, existingTimeout].findResult(Closure.IDENTITY))
    }
    if (cd == null && czlb == null) {
      return null
    }
    LoadBalancerAttributes lba = new LoadBalancerAttributes().withAdditionalAttributes(Collections.emptyList())
    if (cd != null) {
      lba.setConnectionDraining(cd)
    }
    if (czlb != null) {
      lba.setCrossZoneLoadBalancing(czlb)
    }
    if (cs != null) {
      lba.setConnectionSettings(cs)
    }
    return lba
  }

  void "should restore listener policies when updating an existing load balancer"() {
    given:
    def httpListener = new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8502)
    def httpsListener = new Listener(protocol: "HTTPS", loadBalancerPort: 443, instanceProtocol: "HTTP", instancePort: 7001, sSLCertificateId: "foo")
    def policies = ["cookiePolicy"]

    def existingLB = new LoadBalancerDescription(
      loadBalancerName: "kato-main-frontend",
      listenerDescriptions: [
        new ListenerDescription(listener: httpListener),
        new ListenerDescription(listener: httpsListener, policyNames: policies)
      ]
    )

    and:
    description.subnetType = "internal"
    description.setIsInternal(true)
    description.vpcId = "vpcId"

    // request listeners
    description.listeners.clear()
    description.listeners.addAll(
      [
        new UpsertAmazonLoadBalancerClassicDescription.Listener(
          externalProtocol: UpsertAmazonLoadBalancerClassicDescription.Listener.ListenerType.HTTP,
          externalPort: httpListener.loadBalancerPort,
          internalPort: httpListener.instancePort
        ),
        new UpsertAmazonLoadBalancerClassicDescription.Listener(
          externalProtocol: UpsertAmazonLoadBalancerClassicDescription.Listener.ListenerType.HTTPS,
          externalPort: httpsListener.loadBalancerPort,
          internalPort: httpsListener.instancePort,
          sslCertificateId: "bar" //updated cert on listener
        )
    ])

    when:
    operation.operate([])

    then:
    1 * loadBalancing.describeLoadBalancers(_) >> new DescribeLoadBalancersResult(loadBalancerDescriptions: [existingLB])
    1 * loadBalancing.describeLoadBalancerAttributes(_) >> new DescribeLoadBalancerAttributesResult()
    1 * loadBalancing.deleteLoadBalancerListeners({
      it.loadBalancerPorts == [httpsListener.loadBalancerPort]
    } as DeleteLoadBalancerListenersRequest)

    1 * loadBalancing.createLoadBalancerListeners(*_) >> { args ->
      def request = args[0] as CreateLoadBalancerListenersRequest
      assert request.loadBalancerName == description.name
      assert request.listeners.size() == 1
      assert request.listeners*.loadBalancerPort == [ httpsListener.loadBalancerPort ]
    }

    1 * loadBalancing.configureHealthCheck(new ConfigureHealthCheckRequest(
      loadBalancerName: "kato-main-frontend",
      healthCheck: new HealthCheck(
        target: "HTTP:7001/health",
        interval: 10,
        timeout: 5,
        unhealthyThreshold: 2,
        healthyThreshold: 10
      )
    ))

    1 * loadBalancing.modifyLoadBalancerAttributes(new ModifyLoadBalancerAttributesRequest(
      loadBalancerName: "kato-main-frontend",
      loadBalancerAttributes: new LoadBalancerAttributes(
        crossZoneLoadBalancing: new CrossZoneLoadBalancing(enabled: true),
        connectionDraining: new ConnectionDraining(enabled: false),
        additionalAttributes: [],
        connectionSettings:  new ConnectionSettings(idleTimeout: 60)
      )
    ))

    1 * loadBalancing.setLoadBalancerPoliciesOfListener(*_) >> { args ->
      def request = args[0] as SetLoadBalancerPoliciesOfListenerRequest
      assert request.loadBalancerName == description.name
      assert request.policyNames == policies
      assert request.loadBalancerPort == httpsListener.loadBalancerPort
    }
  }

  void "should attempt to apply all listener modifications regardless of individual failures"() {
    given:
    def existingLoadBalancers = [
      new LoadBalancerDescription(loadBalancerName: "kato-main-frontend")
        .withListenerDescriptions(
        new ListenerDescription().withListener(new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8501))
      )
    ]
    description.listeners.clear()
    description.listeners.add(
      new UpsertAmazonLoadBalancerClassicDescription.Listener(
        externalProtocol: UpsertAmazonLoadBalancerClassicDescription.Listener.ListenerType.TCP,
        externalPort: 22,
        internalPort: 22
      ))
    description.listeners.add(
      new UpsertAmazonLoadBalancerClassicDescription.Listener(
        externalProtocol: UpsertAmazonLoadBalancerClassicDescription.Listener.ListenerType.HTTP,
        externalPort: 80,
        internalPort: 8502
      ))

    when:
    operation.operate([])

    then:
    thrown(AtomicOperationException)

    1 * loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])) >>
      new DescribeLoadBalancersResult(loadBalancerDescriptions: existingLoadBalancers)
    1 * loadBalancing.createLoadBalancerListeners(new CreateLoadBalancerListenersRequest(
      loadBalancerName: "kato-main-frontend",
      listeners: [ new Listener(protocol: "TCP", loadBalancerPort: 22, instanceProtocol: "TCP", instancePort: 22) ]
    )) >> { throw new AmazonServiceException("AmazonServiceException") }
    1 * loadBalancing.deleteLoadBalancerListeners(new DeleteLoadBalancerListenersRequest(
      loadBalancerName: "kato-main-frontend", loadBalancerPorts: [80]
    ))
    1 * loadBalancing.createLoadBalancerListeners(new CreateLoadBalancerListenersRequest(
      loadBalancerName: "kato-main-frontend",
      listeners: [ new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8502) ]
    ))
    1 * loadBalancing.applySecurityGroupsToLoadBalancer(new ApplySecurityGroupsToLoadBalancerRequest(
      loadBalancerName: "kato-main-frontend",
      securityGroups: ["sg-1234"]
    ))
    1 * loadBalancing.createLoadBalancerListeners(new CreateLoadBalancerListenersRequest(
      loadBalancerName: 'kato-main-frontend',
      listeners: [ new Listener(protocol: 'HTTP', loadBalancerPort: 80, instanceProtocol: 'HTTP', instancePort: 8501) ]
    ))
    0 * _
  }

  void "should respect crossZone balancing directive"() {
    given:
    def loadBalancer = new LoadBalancerDescription(loadBalancerName: "kato-main-frontend")
    "when requesting crossZone to be disabled, we'll turn it off"
    description.crossZoneBalancing = false
    description.vpcId = "vpcId"

    when:
    operation.operate([])

    then:
    1 * loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])) >>
            new DescribeLoadBalancersResult(loadBalancerDescriptions: [loadBalancer])
    1 * loadBalancing.describeLoadBalancerAttributes(new DescribeLoadBalancerAttributesRequest(loadBalancerName: "kato-main-frontend")) >>
            new DescribeLoadBalancerAttributesResult(loadBalancerAttributes:  new LoadBalancerAttributes(crossZoneLoadBalancing: new CrossZoneLoadBalancing(enabled: true)))
    1 * loadBalancing.modifyLoadBalancerAttributes(_) >> {  ModifyLoadBalancerAttributesRequest request ->
      assert !request.loadBalancerAttributes.crossZoneLoadBalancing.enabled
    }
  }

  void "should handle VPC ELB creation backward compatibility"() {
    given:
    description.subnetType = "internal"
    description.setIsInternal(null)
    description.vpcId = "vpcId"

    when:
    operation.operate([])

    then:
    1 * ingressLoadBalancerBuilder.ingressApplicationLoadBalancerGroup(_, _, _, _, _, _, _) >> new IngressLoadBalancerBuilder.IngressLoadBalancerGroupResult("sg-1234", "kato-elb")

    and:
    1 * loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])) >> null
    1 * loadBalancing.createLoadBalancer(new CreateLoadBalancerRequest(
            loadBalancerName: "kato-main-frontend",
            listeners: [
                    new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8501)
            ],
            subnets: ["subnet1"],
            securityGroups: ["sg-1234"],
            tags: [],
            scheme: "internal"
    )) >> new CreateLoadBalancerResult(dNSName: "dnsName1")
    1 * mockSubnetAnalyzer.getSubnetIdsForZones(["us-east-1a"], "internal", SubnetTarget.ELB, 1) >> ["subnet1"]
  }

  void "should handle VPC ELB creation"() {
    given:
    description.subnetType = "internal"
    description.setIsInternal(true)
    description.vpcId = "vpcId"

    when:
    operation.operate([])

    then:
    1 * ingressLoadBalancerBuilder.ingressApplicationLoadBalancerGroup(_, _, _, _, _, _, _) >> new IngressLoadBalancerBuilder.IngressLoadBalancerGroupResult("sg-1234", "kato-elb")

    and:
    1 * loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-main-frontend"])) >> null
    1 * loadBalancing.createLoadBalancer(new CreateLoadBalancerRequest(
            loadBalancerName: "kato-main-frontend",
            listeners: [
                    new Listener(protocol: "HTTP", loadBalancerPort: 80, instanceProtocol: "HTTP", instancePort: 8501)
            ],
            subnets: ["subnet1"],
            securityGroups: ["sg-1234"],
            tags: [],
            scheme: "internal"
    )) >> new CreateLoadBalancerResult(dNSName: "dnsName1")
    1 * mockSubnetAnalyzer.getSubnetIdsForZones(["us-east-1a"], "internal", SubnetTarget.ELB, 1) >> ["subnet1"]
  }

  void "should use clusterName if name not provided"() {
    given:
    description.clusterName = "kato-test"
    description.name = null
    description.vpcId = "vpcId"

    when:
    operation.operate([])

    then:
    1 * ingressLoadBalancerBuilder.ingressApplicationLoadBalancerGroup(_, _, _, _, _, _, _) >> new IngressLoadBalancerBuilder.IngressLoadBalancerGroupResult("sg-1234", "kato-elb")

    and:
    1 * loadBalancing.describeLoadBalancers(new DescribeLoadBalancersRequest(loadBalancerNames: ["kato-test-frontend"])) >>
            new DescribeLoadBalancersResult(loadBalancerDescriptions: [])
    1 * loadBalancing.createLoadBalancer() { createLoadBalancerRequest ->
      createLoadBalancerRequest.loadBalancerName == "kato-test-frontend"
    } >> new CreateLoadBalancerResult(dNSName: "dnsName1")
  }

  void "should reset existing listeners on a load balancer that already exists"() {
    given:
    def listener = new ListenerDescription().withListener(new Listener("HTTP", 111, 80))
    def loadBalancer = new LoadBalancerDescription(listenerDescriptions: [listener])
    description.vpcId = "vpcId"

    when:
    operation.operate([])

    then:
    1 * loadBalancing.describeLoadBalancers(_) >> new DescribeLoadBalancersResult(loadBalancerDescriptions: [loadBalancer])
    1 * loadBalancing.describeLoadBalancerAttributes(_) >> new DescribeLoadBalancerAttributesResult()
    1 * loadBalancing.deleteLoadBalancerListeners(new DeleteLoadBalancerListenersRequest(loadBalancerPorts: [111]))
    1 * loadBalancing.createLoadBalancerListeners(new CreateLoadBalancerListenersRequest(
            listeners: [ new Listener(loadBalancerPort: 80, instancePort: 8501, protocol: "HTTP", instanceProtocol: "HTTP") ]
    ))
  }

  void "should ignore the old listener of pre-2012 ELBs"() {
    given:
    def oldListener = new ListenerDescription().withListener(new Listener(null, 0, 0))
    def listener = new ListenerDescription().withListener(new Listener("HTTP", 111, 80))
    def loadBalancer = new LoadBalancerDescription(listenerDescriptions: [oldListener, listener])
    description.vpcId = "vpcId"

    when:
    operation.operate([])

    then:
    1 * loadBalancing.describeLoadBalancers(_) >> new DescribeLoadBalancersResult(loadBalancerDescriptions: [loadBalancer])
    1 * loadBalancing.describeLoadBalancerAttributes(_) >> new DescribeLoadBalancerAttributesResult()
    1 * loadBalancing.deleteLoadBalancerListeners(new DeleteLoadBalancerListenersRequest(loadBalancerPorts: [111]))
    0 * loadBalancing.deleteLoadBalancerListeners(_)
    1 * loadBalancing.createLoadBalancerListeners(new CreateLoadBalancerListenersRequest(
      listeners: [ new Listener(loadBalancerPort: 80, instancePort: 8501, protocol: "HTTP", instanceProtocol: "HTTP") ]
    ))
    0 * loadBalancing.createLoadBalancerListeners(_)
  }


  @Unroll
  void "should enable AWS Shield protection if external ELB"() {
    given:
    description.credentials = TestCredential.named('bar', [shieldEnabled: shieldEnabled])
    description.shieldProtectionEnabled = descriptionOverride
    description.vpcId = "vpcId"

    when:
    operation.operate([])

    then:
    1 * ingressLoadBalancerBuilder.ingressApplicationLoadBalancerGroup(_, _, _, _, _, _, _) >> new IngressLoadBalancerBuilder.IngressLoadBalancerGroupResult("sg-1234", "kato-elb")

    1 * loadBalancing.createLoadBalancer(_ as CreateLoadBalancerRequest) >> new CreateLoadBalancerResult(dNSName: 'dnsName1')
    (shouldProtect ? 1 : 0) * awsShield.createProtection(new CreateProtectionRequest(
      name: 'kato-main-frontend',
      resourceArn: 'arn:aws:elasticloadbalancing:123456789012bar:us-east-1:loadbalancer/kato-main-frontend'
    ))

    where:
    shieldEnabled | descriptionOverride || shouldProtect
    false         | false               || false
    false         | true                || false
    true          | false               || false
    true          | true                || true
  }
}
