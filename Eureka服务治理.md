### Eureka服务治理

#### 功能说明

主要用来实现各个为服务实例的自动化注册与发现。

#### 服务注册

服务进程主动向注册中心注册自己的服务，注册中心维护如下图示列表，并以心跳检测的方式排除故障服务。

<!--more-->

| 服务名  | 位置                                       |
| :--: | :--------------------------------------- |
| 服务A  | 192.168.0.100:8000、192.168.0.101:8000    |
| 服务B  | 192.168.0.100:9000、192.168.0.101:9000、192.168.0.102:9000192.168.0.100:9000、192.168.0.101:9000、192.168.0.102:9000 |

#### 服务发现

一般情况下、服务调用方发起服务调用时是不知道被调用服务的位置的，所以需要先咨询服务注册中心，获取所有服务清单，然后服务调用方依据获取到的清单选择一个被调用服务的位置（如服务B，选择192.168.0.101:9000）。即、客户端负载均衡。

##### Ribbon Consumer在服务发现阶段的源码流程

1.启动类上注解`@EnableDiscoveryClient` ,点进去发现该注解的注释说这个注解是为了实例化`DiscoveryClient` ,因为注解解析是基于反射技术，所以不能点进去源码，双shit键开启搜索，搜索这个`DiscoveryClient` ,发现不知如何继续跟踪，算了。挖掘配置信息`eureka.client.serviceUrl.defaultZone=` , 它主要告诉服务消费者服务注册中心在哪里，消费者要去服务注册中心拉去服务列表。



- `EurekaServerConfigBean` (服务端配置) : 类中的属性可以在配置文件中以 eureka.server 为前缀进行个性化配置。

- `EurekaClientConfigBean` (客户端配置->服务注册类配置): 类中的属性可以在配置文件中以eureka.client为前缀进行个性化配置。

- `EurekaInstanceConfigBean` (客户端配置->服务实例类配置信息): eureka.instance为前缀。

  * 元数据：Eureka客户端在向服务端注册服务时，用来描述自身服务相关信息的对象。其中包含了标准化元数据（eg.服务名称、实例名称、实例IP、实例端口等）用于服务治理的信息，此类信息配置用`eureka.instance.<property>=<value>` 进行配置；以及一些用于负载均衡策略或其他用途的自定义元数据，用`eureka.instance.metadataMap.<key>=<value>` 进行配置。
    1. 实例名配置 : 相同服务不同实例需要有独一无二的实例名(原生eureka不支持通主机启动通服务的多个实例，因其通过instanceId来区分同服务不同实例的。Spring Cloud Eureka对实例名默认命名规则进行拓展，为host_name:application_name:instance_id:server_port, 只要其中一个不同就可以。对于同一台主机启动同服务不同实例时，可以通过不同的serverPort加以区分，也可以通过不同实例名+端口号来区分)
    2. 端点配置 : actuator
    3. 健康监测 : 引入starter-actuator依赖----> 配置文件新增`eureka.client.healthcheck.enable=true` 
    4. 其他配置 : 如preferIPAddr优先使用IP作为主机名的标识等

- `DiscoveryClient` : 其构造方法最后会调用`initScheduledTasks()` 方法去初始化服务注册、服务续约、服务获取的定时任务

  ```java
  	/**
       * Initializes all scheduled tasks.
       */
      private void initScheduledTasks() {
          if (clientConfig.shouldFetchRegistry()) {
              // registry cache refresh timer
              int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
              int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();
            	// 这里是初始化 服务获取 定时任务器
              scheduler.schedule(
                      new TimedSupervisorTask(
                              "cacheRefresh",
                              scheduler,
                              cacheRefreshExecutor,
                              registryFetchIntervalSeconds,
                              TimeUnit.SECONDS,
                              expBackOffBound,
                              new CacheRefreshThread()
                      ),
                      registryFetchIntervalSeconds, TimeUnit.SECONDS);
          }

          if (clientConfig.shouldRegisterWithEureka()) {
              int renewalIntervalInSecs = instanceInfo.getLeaseInfo().getRenewalIntervalInSecs();
              int expBackOffBound = clientConfig.getHeartbeatExecutorExponentialBackOffBound();
              logger.info("Starting heartbeat executor: " + "renew interval is: " + renewalIntervalInSecs);

              // Heartbeat timer
            	// 初始化 服务续约 定时任务器(维持周期性心跳)
              scheduler.schedule(
                      new TimedSupervisorTask(
                              "heartbeat",
                              scheduler,
                              heartbeatExecutor,
                              renewalIntervalInSecs,
                              TimeUnit.SECONDS,
                              expBackOffBound,
                              new HeartbeatThread()
                      ),
                      renewalIntervalInSecs, TimeUnit.SECONDS);

              // InstanceInfo replicator
            	// 服务注册 定时任务器的初始化
              instanceInfoReplicator = new InstanceInfoReplicator(
                      this,
                      instanceInfo,
                      clientConfig.getInstanceInfoReplicationIntervalSeconds(),
                      2); // burstSize

              statusChangeListener = new ApplicationInfoManager.StatusChangeListener() {
                  @Override
                  public String getId() {
                      return "statusChangeListener";
                  }

                  @Override
                  public void notify(StatusChangeEvent statusChangeEvent) {
                      if (InstanceStatus.DOWN == statusChangeEvent.getStatus() ||
                              InstanceStatus.DOWN == statusChangeEvent.getPreviousStatus()) {
                          // log at warn level if DOWN was involved
                          logger.warn("Saw local status change event {}", statusChangeEvent);
                      } else {
                          logger.info("Saw local status change event {}", statusChangeEvent);
                      }
                      instanceInfoReplicator.onDemandUpdate();
                  }
              };

              if (clientConfig.shouldOnDemandUpdateStatusChange()) {
                  applicationInfoManager.registerStatusChangeListener(statusChangeListener);
              }

              instanceInfoReplicator.start(clientConfig.getInitialInstanceInfoReplicationIntervalSeconds());
          } else {
              logger.info("Not registering with Eureka server per configuration");
          }
      }
  ```