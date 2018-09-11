### Spring Cloud Ribbon

#### 客户端负载均衡

与服务端负载均衡原理差不多。服务端负载均衡一般多是采用软件负载均衡，其主要方法是使用Nginx+web服务器做的，负载均衡策略很多——轮询、权重、hash等，nginx有的负载均衡策略是基于第三方模块，需要手动编译链接安装。nginx层要维护一个可有服务表，通过心跳的方式检测服务是否可达，以便及时感知并清理掉不可用服务。客户端负载均衡Ribbon的负载均衡策略也是通过维护一张可用服务表，用心跳（就是周期调度的线程池定期发送一个http请求，需要服务端（eureka server）与客户端的配合）检测服务可达性。spring对Http请求做了模板封装——RestTemplate，其能够实现RESTful风格的网络请求（get、put、post、delete），spring cloud组件之间的通信都是基于restTemplate做的。

<!--more-->

#### Ribbon负载均衡策略

- **RoundRobinRule:** 轮询策略，Ribbon以轮询的方式选择服务器，这个是默认值。所以示例中所启动的两个服务会被循环访问;
- **RandomRule:** 随机选择，也就是说Ribbon会随机从服务器列表中选择一个进行访问;
- **BestAvailableRule:** 最大可用策略，即先过滤出故障服务器后，选择一个当前并发请求数最小的;
- **WeightedResponseTimeRule:** 带有加权的轮询策略，对各个服务器响应时间进行加权处理，然后在采用轮询的方式来获取相应的服务器;
- **AvailabilityFilteringRule:** 可用过滤策略，先过滤出故障的或并发请求大于阈值一部分服务实例，然后再以线性轮询的方式从过滤后的实例清单中选出一个;
- **ZoneAvoidanceRule:** 区域感知策略，先使用主过滤条件（区域负载器，选择最优区域）对所有实例过滤并返回过滤后的实例清单，依次使用次过滤条件列表中的过滤条件对主过滤条件的结果进行过滤，判断最小过滤数（默认1）和最小过滤百分比（默认0），最后对满足条件的服务器则使用RoundRobinRule(轮询方式)选择一个服务器实例。

> 我们可以通过继承`ClientConfigEnabledRoundRobinRule`，来实现自己负载均衡策略。

#### 实现方式举例及源码分析

- 服务提供者需要启动多个服务实例并注册到一个或则多个相互关联的服务注册中心
- 服务消费者直接通过调用被`@LoadBalanced` 注解修饰的RestTemplate来实现调用服务接口

```java
package com.laowang.ribbonconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * @author wangyonghao
 */
@SpringBootApplication
@EnableDiscoveryClient
public class RibbonConsumerApplication {

    @Bean
    @LoadBalanced//添加此注解，解析方面有别于普通restTemplate，其调用接口的uri的hostName不是ip，而是服务名，所以Ribbon还要做一下解析，否则该restTemplate Bean将不能正常工作
    public RestTemplate restTemplate(){
        return new RestTemplate();
    }

    public static void main(String[] args) {
        SpringApplication.run(RibbonConsumerApplication.class, args);
    }
}
```

#### Ribbon如何实现负载均衡的

##### 请求的拦截

看`@LoadBalanced` 源码，其注释如下

> Annotation to mark a RestTemplate bean to be configured to use a LoadBalancerClient
>
> // 该注解标记一个使用负载均衡的客户端( LoadBalancerClient)来配置的restTemplate bean

查一下 `LoadBalancerClient` ，其是一个接口，内容如下

```java
/**
 * Represents a client side load balancer
 * @author Spencer Gibb
 */
public interface LoadBalancerClient extends ServiceInstanceChooser {

	/**
	 * execute request using a ServiceInstance from the LoadBalancer for the specified
	 * service
	 * @param serviceId the service id to look up the LoadBalancer
	 * @param request allows implementations to execute pre and post actions such as
	 * incrementing metrics
	 * @return the result of the LoadBalancerRequest callback on the selected
	 * ServiceInstance
	 * 根据传入的服务名serviceId在负载均衡器中选择一个LoadBalancer再来执行request，下面是其重载函数
	 */
	<T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException;

	/**
	 * execute request using a ServiceInstance from the LoadBalancer for the specified
	 * service
	 * @param serviceId the service id to look up the LoadBalancer
	 * @param serviceInstance the service to execute the request to
	 * @param request allows implementations to execute pre and post actions such as
	 * incrementing metrics
	 * @return the result of the LoadBalancerRequest callback on the selected
	 * ServiceInstance
	 */
	<T> T execute(String serviceId, ServiceInstance serviceInstance, LoadBalancerRequest<T> request) throws IOException;

	/**
	 * Create a proper URI with a real host and port for systems to utilize.
	 * Some systems use a URI with the logical serivce name as the host,
	 * such as http://myservice/path/to/service.  This will replace the
	 * service name with the host:port from the ServiceInstance.
	 * @param instance
	 * @param original a URI with the host as a logical service name
	 * @return a reconstructed URI
	 * 重构URI，因为有些服务是逻辑名如http://myservice/path/to/service，此方法将根据instance信息将		 * uri转成host:port
	 */
	URI reconstructURI(ServiceInstance instance, URI original);
}
```

```java
/**
 * Implemented by classes which use a load balancer to choose a server to
 * send a request to.
 *
 * @author Ryan Baxter
 */
public interface ServiceInstanceChooser {

    /**
     * Choose a ServiceInstance from the LoadBalancer for the specified service
     * @param serviceId the service id to look up the LoadBalancer
     * @return a ServiceInstance that matches the serviceId
     * 针对某一服务的调用，要从loadBalancer中选择一个服务实例
     * （一个服务service对应一个服务名serviceId，多个服务实例serviceInstance）
     */
    ServiceInstance choose(String serviceId);
}
```

同包下类结构图如下：

![](http://onhavnxj2.bkt.clouddn.com/loadbalancer1.png)

从`execute` 函数或则调试（在execute函数内打断点），能追溯到大致执行流程，下面是调用栈

![](http://onhavnxj2.bkt.clouddn.com/%E8%BF%9B%E5%85%A5cloud%E7%BB%84%E4%BB%B6%E9%80%BB%E8%BE%91.jpg)

从底向上，在`org.springframework.http.client` 中，执行如下逻辑

```java
if (this.iterator.hasNext()) {
				ClientHttpRequestInterceptor nextInterceptor = this.iterator.next();
				return nextInterceptor.intercept(request, body, this);
			}
```

即，它根据构建时传递的迭代器---->`private final Iterator<ClientHttpRequestInterceptor> iterator;` 此时spring容器中关于此迭代器中只有两个元素，第一个是`MetricsClientHttpRequestInterceptor` 、第二个是`LoadBalancerInterceptor` ，二者都是接口`ClientHttpRequestInterceptor` 的实现。`ClientHttpRequestInterceptor` 的实现类，如下图：

![](http://onhavnxj2.bkt.clouddn.com/%E6%8B%A6%E6%88%AA%E5%99%A8%E5%AE%9E%E7%8E%B0%E7%B1%BB.jpg)

根据调用栈图可知，执行逻辑先进入`MetricsClientHttpRequestInterceptor` 类，其工作是

> Intercepts RestTemplate requests and records metrics about execution time and results.
>
> 拦截restTemplate请求并记录其关于执行时间和执行结果的指数

之后进入`LoadBalancerInterceptor` 类，也就是调用栈标号为2的那一行，其逻辑代码如下

```java
@Override
	public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
			final ClientHttpRequestExecution execution) throws IOException {
		final URI originalUri = request.getURI();
		String serviceName = originalUri.getHost();
		Assert.state(serviceName != null, "Request URI does not contain a valid hostname: " + originalUri);
		return this.loadBalancer.execute(serviceName, requestFactory.createRequest(request, body, execution));
	}
```

其拦截方法`intercept` 中使用了类属性`loadBalancer` 的`execute` 方法，其定义与构造函数如下

```java
private LoadBalancerClient loadBalancer;
	private LoadBalancerRequestFactory requestFactory;

	public LoadBalancerInterceptor(LoadBalancerClient loadBalancer, LoadBalancerRequestFactory requestFactory) {
		this.loadBalancer = loadBalancer;
		this.requestFactory = requestFactory;
	}
```

属性`loadBalancer` 是接口`LoadBalancerClient` 的某一实现类（这里是`org.springframework.cloud.netflix.ribbon.RibbonLoadBalancerClient`）的一个实例对象。

结合上面分析，知道了当服务消费者在调用服务API时，客户端负载均衡是如何拦截到restTemplate请求的，也即是如何进入到Spring Cloud组件的：`org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor` 通过继承接口`org.springframework.http.client.ClientHttpRequestInterceptor` 并实例化一个bean把拦截逻辑“拉”到Spring Cloud组件中,`LoadBalancerInterceptor` 会调用`LoadBalancerClient` 某一实现类(`RibbonLoadBalancerClient` )的实例化对象的execute方法，这就回到了注解`@LoadBalanced` 的作用——标记一个需要特殊配置的`restTemplate`对象。

##### 负载均衡的实现与实施

继续跟进调试，代码如下：

```java
@Override
	public <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException {
		ILoadBalancer loadBalancer = getLoadBalancer(serviceId);
		Server server = getServer(loadBalancer);
		if (server == null) {
			throw new IllegalStateException("No instances available for " + serviceId);
		}
		RibbonServer ribbonServer = new RibbonServer(serviceId, server, isSecure(server,
				serviceId), serverIntrospector(serviceId).getMetadata(server));

		return execute(serviceId, ribbonServer, request);
	}
```

1. 在`RibbonLoadBalancerClient`的`execute`方法内，首先根据服务名获取负载均衡器`ILoadBalancer`

2. 通过负载均衡器`public Server chooseServer(Object key)` 方法获取到服务的一个实例,其先进入到`ZoneAwareLoadBalancer` 调用父类`BaseLoadBalancer`的`chooseServer`方法，在父类的该方法内，会根据IRule（负载均衡策略，可配置，默认值`ZoneAvoidanceRule` ）所对应的负载均衡策略选择服务的一个实例。

   - 在这里涉及到负载均衡策略，默认是`ZoneAvoidanceRule` 即轮询
   - 可用负载均衡策略如下

   ![](http://onhavnxj2.bkt.clouddn.com/%E8%B4%9F%E8%BD%BD%E5%9D%87%E8%A1%A1%E7%AD%96%E7%95%A5.jpg)

3. 回到`execute`函数，接下来根据服务名，服务实例，是否需要Https，以及服务实例的一些元信息构造出一个`RibbonServer` （静态内部类，主要描述RibbonServer的pojo）对象。

4. 将服务名和包装的服务实例以及请求传给重载函数`execute` 代码如下：

   ```java
   @Override
   	public <T> T execute(String serviceId, ServiceInstance serviceInstance, LoadBalancerRequest<T> request) throws IOException {
           // 类型装换与参数检查
   		Server server = null;
   		if(serviceInstance instanceof RibbonServer) {
   			server = ((RibbonServer)serviceInstance).getServer();
   		}
   		if (server == null) {
   			throw new IllegalStateException("No instances available for " + serviceId);
   		}
   		// 根据服务名获取负载均衡上下文（包含负载均衡的很多信息的一个对象）
   		RibbonLoadBalancerContext context = this.clientFactory
   				.getLoadBalancerContext(serviceId);
           // 构建一个Ribbon状态记录器对象, 用于跟中请求的状态，包括开始时间、结束时间等
   		RibbonStatsRecorder statsRecorder = new RibbonStatsRecorder(context, server);

   		try {
             	// 进入到LoadBalancerRequestFactory类的apply方法，通过request、服务实例和负载均衡器
             	// 创建ServiceRequestWrapper对象,之后解析逻辑地址url，请求头，封装成一个
             	// ClientHttpRequest类型的对象delegate，之后如果请求体内有数据，则copy到delegate的请
             	// 求体内，最后调用delegate.execute(),获取到相应对象returnVal
   			T returnVal = request.apply(serviceInstance);
             	// Ribbon状态记录器对象解析一下response，record请求的一些状态信息
   			statsRecorder.recordStats(returnVal);
             	// 返回响应
   			return returnVal;
   		}
   		// catch IOException and rethrow so RestTemplate behaves correctly
   		catch (IOException ex) {
   			statsRecorder.recordStats(ex);
   			throw ex;
   		}
   		catch (Exception ex) {
   			statsRecorder.recordStats(ex);
   			ReflectionUtils.rethrowRuntimeException(ex);
   		}
   		return null;
   	}
   ```

至此，网络请求在Spring Cloud中算是绕完了，总结一下: Ribbon服务消费端通过注解`@LoadBalanced` 开启了客户端负载均衡，其主要目的是要让restTemplate经过RibbonLoadBalancerClient处理，具体是通过拦截器`LoadBalancerInterceptor` 进入的。然后根据服务名获取负载均衡器、根据负载均衡器以及配置的负载均衡策略(或则默认的负载均衡策略)选择出一个将要被请求的服务实例，之后调用apply方法，在LoadBalancerRequestFactory类中解析服务实例的hostname等元信息(如url)、包装request发送请求获取相应。

#### 负载均衡器分析

Spring Cloud定义了LoadBalancerClient作为负载均衡器的接口，并且对Ribbon也给出了具体实现RibbonLoadBalancerClient，由上面分析可知在execute方法中根据服务名获取负载均衡器时使用的是Ribbon的ILoadBalancer接口实现，下面就罗列一下其对应的实现类

![](http://onhavnxj2.bkt.clouddn.com/%E8%B4%9F%E8%BD%BD%E5%9D%87%E8%A1%A1%E5%99%A8%E7%9A%84%E5%AE%9E%E7%8E%B0.jpg)

- `AbstractLoadBalancer` 定义了服务实例和获取选择

  ```java
  public abstract class AbstractLoadBalancer implements ILoadBalancer {
      // 枚举类，三种服务（所有服务实例，正常服务实例，停止服务实例）
      public enum ServerGroup{
          ALL,
          STATUS_UP,
          STATUS_NOT_UP        
      }
          
      /** delegate to {@link #chooseServer(Object)} with parameter null.*/
      public Server chooseServer() {
         return chooseServer(null);
      }
      
      /**
       * List of servers that this Loadbalancer knows about
       * @param serverGroup Servers grouped by status, e.g., {@link ServerGroup#STATUS_UP}
       */
      public abstract List<Server> getServerList(ServerGroup serverGroup);
      
      /** Obtain LoadBalancer related Statistics*/
      public abstract LoadBalancerStats getLoadBalancerStats();    
  }
  ```

- `BaseLoadBalancer`主要属性如下，定义很多负载均衡器基础相关的内容

```java
/**
 * A basic implementation of the load balancer where an arbitrary list of
 * servers can be set as the server pool. A ping can be set to determine the
 * liveness of a server. Internally, this class maintains an "all" server list
 * and an "up" server list and use them depending on what the caller asks for.
 */
public class BaseLoadBalancer extends AbstractLoadBalancer implements
        PrimeConnections.PrimeConnectionListener, IClientConfigAware {

    private static Logger logger = LoggerFactory
            .getLogger(BaseLoadBalancer.class);
    private final static IRule DEFAULT_RULE = new RoundRobinRule();
    private final static SerialPingStrategy DEFAULT_PING_STRATEGY = new SerialPingStrategy();
    private static final String DEFAULT_NAME = "default";
    private static final String PREFIX = "LoadBalancer_";

    protected IRule rule = DEFAULT_RULE;

    protected IPingStrategy pingStrategy = DEFAULT_PING_STRATEGY;

    protected IPing ping = null;

    @Monitor(name = PREFIX + "AllServerList", type = DataSourceType.INFORMATIONAL)
    protected volatile List<Server> allServerList = Collections
            .synchronizedList(new ArrayList<Server>());
    @Monitor(name = PREFIX + "UpServerList", type = DataSourceType.INFORMATIONAL)
    protected volatile List<Server> upServerList = Collections
            .synchronizedList(new ArrayList<Server>());

    protected ReadWriteLock allServerLock = new ReentrantReadWriteLock();
    protected ReadWriteLock upServerLock = new ReentrantReadWriteLock();

    protected String name = DEFAULT_NAME;

    protected Timer lbTimer = null;
    protected int pingIntervalSeconds = 10;
    protected int maxTotalPingTimeSeconds = 5;
    protected Comparator<Server> serverComparator = new ServerComparator();

    protected AtomicBoolean pingInProgress = new AtomicBoolean(false);

    protected LoadBalancerStats lbStats;

    private volatile Counter counter = Monitors.newCounter("LoadBalancer_ChooseServer");

    private PrimeConnections primeConnections;

    private volatile boolean enablePrimingConnections = false;
    
    private IClientConfig config;
    
    private List<ServerListChangeListener> changeListeners = new CopyOnWriteArrayList<ServerListChangeListener>();

    private List<ServerStatusChangeListener> serverStatusListeners = new CopyOnWriteArrayList<ServerStatusChangeListener>();
    // ...
}
```

- `DynamicServerListLoadBalancer<T extends Server>`全部参数如下，具备服务清单运行期间动态更新以及对服务实例过滤的功能

```java
boolean isSecure = false;
boolean useTunnel = false;

// to keep track of modification of server lists
protected AtomicBoolean serverListUpdateInProgress = new AtomicBoolean(false);

volatile ServerList<T> serverListImpl;

volatile ServerListFilter<T> filter;

protected final ServerListUpdater.UpdateAction updateAction = new ServerListUpdater.UpdateAction() {
    @Override
    public void doUpdate() {
        updateListOfServers();
    }
};

protected volatile ServerListUpdater serverListUpdater;
```

- `NoOpLoadBalancer` 都是空函数

![](http://onhavnxj2.bkt.clouddn.com/NoLoandBalance.jpg)

- `ZoneAwareLoadBalancer<T extends Server>` 

![](http://onhavnxj2.bkt.clouddn.com/ZoneAwareLoadBalancer.png)

#### Eureka Server、Eureka Client（Service）、Eureka Client（Consumer）之间关系

![](https://upload-images.jianshu.io/upload_images/1488771-4aa4072bff40a204.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1000/format/webp)

