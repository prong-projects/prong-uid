UidGenerator
==========================
## 前言

本组件是 [百度UID](https://github.com/baidu/uid-generator) 的一个派生版本，改造为基于spring boot的版本，并封装为starter的方式，以方便作为组件引入到spring boot项目。

工程结构说明：

```shell
├── LICENSE
├── README.md
├── logs			# 日志目录
├── pom.xml			# 父POM
├── uid-consumer	# spring boot应用
└── uid-generator	# UID组件
```

## 概述

UidGenerator是Java实现的，基于[Snowflake](https://github.com/twitter/snowflake)算法的唯一ID生成器。UidGenerator以组件形式工作在应用项目中,
支持自定义workerId位数和初始化策略, 从而适用于[docker](https://www.docker.com/)等虚拟化环境下实例自动重启、漂移等场景。

在实现上, UidGenerator通过借用未来时间来解决sequence天然存在的并发限制; 采用RingBuffer来缓存已生成的UID, 并行化UID的生产和消费，同时对CacheLine补齐，避免了由RingBuffer带来的硬件级「伪共享」问题. 最终单机QPS可达<font color=red>600万</font>。

依赖版本：[Java8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html)及以上版本，[MySQL](https://dev.mysql.com/downloads/mysql/)(内置WorkerID分配器, 启动阶段通过DB进行分配; 如自定义实现, 则DB非必选依赖）。

Snowflake算法
-------------
![Snowflake](uid-generator/doc/snowflake.png)  
Snowflake算法描述：指定机器 & 同一时刻 & 某一并发序列，是唯一的。据此可生成一个64 bits的唯一ID（long）。默认采用上图字节分配方式：

* sign(1bit)  

  固定1bit符号标识，即生成的UID为正数。

* delta seconds (28 bits)  

  当前时间，相对于时间基点"2016-05-20"的增量值，单位：秒，最多可支持约8.7年

* worker id (22 bits)  

  机器id，最多可支持约420w次机器启动。内置实现为在启动时由数据库分配，默认分配策略为用后即弃，后续可提供复用策略。

* sequence (13 bits)   

  每秒下的并发序列，13 bits可支持每秒8192个并发。

**以上参数均可通过application.yml进行自定义**：

```yaml
prong: 
  uid: 
    timeBits: 29
    workerBits: 21
    seqBits: 13
    epochStr: "2018-11-26"
```

## 组件功能简述

UidGenerator在应用中是以 Spring 组件的形式提供服务。

- `DefaultUidGenerator`提供了最简单的Snowflake式的生成模式，但是并没有使用任何缓存来预存UID，在需要生成ID的时候即时进行计算。
- `CachedUidGenerator`是一个使用RingBuffer预先缓存UID的生成器，在初始化时就会填充整个RingBuffer，并在take()时检测到少于指定的填充阈值之后就会异步地再次填充RingBuffer（默认值为50%），另外可以启动一个定时器周期性检测阈值并及时进行填充。


CachedUidGenerator
-------------------
RingBuffer环形数组，数组每个元素成为一个slot。RingBuffer容量，默认为Snowflake算法中sequence最大值，且为2^N。可通过```boostPower```配置进行扩容，以提高RingBuffer读写吞吐量。

Tail指针、Cursor指针用于环形数组上读写slot：

* Tail指针

  表示Producer生产的最大序号(此序号从0开始，持续递增)。Tail不能超过Cursor，即生产者不能覆盖未消费的slot。当Tail已赶上curosr，此时可通过`rejectedPutBufferHandler`指定PutRejectPolicy。

* Cursor指针

  表示Consumer消费到的最小序号(序号序列与Producer序列相同)。Cursor不能超过Tail，即不能消费未生产的slot。当Cursor已赶上tail，此时可通过```rejectedTakeBufferHandler```指定TakeRejectPolicy。

![RingBuffer](uid-generator/doc/ringbuffer.png)  

CachedUidGenerator采用了双RingBuffer，Uid-RingBuffer用于存储Uid、Flag-RingBuffer用于存储Uid状态(是否可填充、是否可消费)。由于数组元素在内存中是连续分配的，可最大程度利用CPU cache以提升性能。但同时会带来「伪共享」FalseSharing问题，为此在Tail、Cursor指针、Flag-RingBuffer中采用了CacheLine补齐方式。

![FalseSharing](uid-generator/doc/cacheline_padding.png) 

#### RingBuffer填充时机 ####
* 初始化预填充

  RingBuffer初始化时，预先填充满整个RingBuffer。

* 即时填充

  Take消费时，即时检查剩余可用slot量(```tail``` - ```cursor```)，如小于设定阈值，则补全空闲slots。阈值可通过```paddingFactor```来进行配置，请参考Quick Start中CachedUidGenerator配置。

* 周期填充（默认不启用）

  通过Schedule线程，定时补全空闲slots。可通过```scheduleInterval```配置，以应用定时填充功能，并指定Schedule时间间隔。


Quick Start
------------

### 运行单元测试

#### 步骤1: 安装依赖
先下载[Java8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html), [MySQL](https://dev.mysql.com/downloads/mysql/)和[Maven](https://maven.apache.org/download.cgi)

##### 设置环境变量
maven无须安装, 设置好MAVEN_HOME即可. 可像下述脚本这样设置JAVA_HOME和MAVEN_HOME, 如已设置请忽略.
```shell
export MAVEN_HOME=/xxx/xxx/software/maven/apache-maven-3.3.9
export PATH=$MAVEN_HOME/bin:$PATH
JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk1.8.0_91.jdk/Contents/Home";
export JAVA_HOME;
```

#### 步骤2: 创建表WORKER_NODE
运行sql脚本以导入表WORKER_NODE，脚本如下：
```sql
DROP DATABASE IF EXISTS `xxxx`;
CREATE DATABASE `xxxx` ;
use `xxxx`;
DROP TABLE IF EXISTS WORKER_NODE;
CREATE TABLE WORKER_NODE
(
ID BIGINT NOT NULL AUTO_INCREMENT COMMENT 'auto increment id',
HOST_NAME VARCHAR(64) NOT NULL COMMENT 'host name',
PORT VARCHAR(64) NOT NULL COMMENT 'port',
TYPE INT NOT NULL COMMENT 'node type: ACTUAL or CONTAINER',
LAUNCH_DATE DATE NOT NULL COMMENT 'launch date',
MODIFIED TIMESTAMP NOT NULL COMMENT 'modified time',
CREATED TIMESTAMP NOT NULL COMMENT 'created time',
PRIMARY KEY(ID)
)
 COMMENT='DB WorkerID Assigner for UID Generator',ENGINE = INNODB;
```

#### 步骤3: 修改Spring Boot配置
提供了两种生成器：[DefaultUidGenerator](uid-generator/src/main/java/io/prong/uid/impl/DefaultUidGenerator.java)、[CachedUidGenerator](uid-generator/src/main/java/io/prong/uid/impl/CachedUidGenerator.java)。如对UID生成性能有要求，请使用CachedUidGenerator。

#### DefaultUidGenerator配置

在 *[application.yml](uid-generator/src/test/resources/application.yml)* 中配置ID生成规则：

```yaml
# 以下为可选配置, 如未指定将采用默认值
prong: 
  uid: 
    timeBits: 29
    workerBits: 21
    seqBits: 13
    epochStr: "2018-11-26"
```

spring boot 中生成 WorkerIdAssigner 接口的一个实例：

```java
@Bean
@ConditionalOnMissingBean
WorkerIdAssigner workerIdAssigner() {
	return new DisposableWorkerIdAssigner();
}
```

> 注意：DisposableWorkerIdAssigner 可以根据需要替换成其他实现。

#### CachedUidGenerator配置

在 *[application.yml](uid-generator/src/test/resources/application.yml)* 中配置ID生成规则：

```yaml
# 以下为可选配置, 如未指定将采用默认值
prong: 
  uid: 
    timeBits: 29
    workerBits: 21
    seqBits: 13
    epochStr: "2018-11-26"
    CachedUidGenerator:
      boost-power: 3          # RingBuffer size扩容参数, 可提高UID生成的吞吐量, 默认:3
      padding-factor: 50      # 指定何时向RingBuffer中填充UID, 取值为百分比(0, 100), 默认为50
      #schedule-interval: 60  # 默认:不配置此项, 即不实用Schedule线程. 如需使用, 请指定Schedule线程时间间隔, 单位:秒
```

spring boot 中生成 WorkerIdAssigner 接口的一个实例：

```java
@Bean
@ConditionalOnMissingBean
WorkerIdAssigner workerIdAssigner() {
	return new DisposableWorkerIdAssigner();
}
```

> 注意：DisposableWorkerIdAssigner 可以根据需要替换成其他实现。

根据需要指定2个拒绝策略的接口实现（*默认无需指定*）：

- RejectedPutBufferHandler接口：拒绝策略: 当环已满, 无法继续填充。
  默认无需指定, 将丢弃Put操作, 仅日志记录. 如有特殊需求, 请实现该接口(支持Lambda表达式)。
- RejectedTakeBufferHandler接口：拒绝策略: 当环已空, 无法继续获取时。
  默认无需指定, 将记录日志, 并抛出UidGenerateException异常. 如有特殊需求, 请实现该接口(支持Lambda表达式)。

例如，下面实现了一个当环已满, 无法继续填充时的自定义策略：

```java
@Component
public class CustomRejectedPutBufferHandler implements RejectedPutBufferHandler {

	/**
	 * 只打印，不记日志
	 */
	@Override
	public void rejectPutBuffer(RingBuffer ringBuffer, long uid) {
		System.out.format("Rejected putting buffer for uid:{%d}. {%s}\r\n", uid, ringBuffer.toString());
	}
}
```

#### Mybatis配置

在 *[application.yml](uid-generator/src/test/resources/application.yml)* 中配置数据源和mybatis：

```yaml
mybatis: 
  configuration:
    default-fetch-size: 100
    default-statement-timeout: 30
    map-underscore-to-camel-case: true
  mapper-locations: classpath*:mapper/**/*.xml
mapper: 
  mappers: 
    - tk.mybatis.mapper.common.Mapper
spring: 
  datasource: 
    driver-class-name: com.mysql.jdbc.Driver
    druid: 
      filters: stat
      defaultAutoCommit: true
      initialSize: 2
      max-active: 10
      min-idle: 1
      max-pool-prepared-statement-per-connection-size: -1
      max-wait: 5000
      pool-prepared-statements: false
      test-on-borrow: false
      test-on-return: false
      test-while-idle: true
      validation-query: SELECT 1 FROM DUAL
    url: jdbc:mysql://localhost:xxxx/xxxx
    username: xxxx
    password: xxxx
```

修改 *[application.yml](uid-generator/src/test/resources/application.yml)* 配置中，url、username 和 password，确保mysql地址、名称、端口号、用户名和密码正确。

#### 步骤4: 运行示例单测

运行单测[CachedUidGeneratorTest](src/test/java/com/baidu/fsg/uid/CachedUidGeneratorTest.java), 展示UID生成、解析等功能
```java
@Resource
private UidGenerator uidGenerator;

@Test
public void testSerialGenerate() {
    // Generate UID
    long uid = uidGenerator.getUID();

    // Parse UID into [Timestamp, WorkerId, Sequence]
    // {"UID":"180363646902239241","parsed":{    "timestamp":"2017-01-19 12:15:46",    "workerId":"4",    "sequence":"9"        }}
    System.out.println(uidGenerator.parseUID(uid));

}
```

### 在Spring Boot项目使用UID组件

本项目提供了一个名为 uid-consumer 的 Spring Boot 的项目例子以供参考。

运行步骤：

1、参考单元测试修改uid-consumer的 *[application.yml](uid-consumer/src/main/resources/application.yml)*；

2、启动 uid-consumer 应用；

3、在浏览器访问：

- http://localhost:9999/testdefaultuid, 生成UID
- http://localhost:9999/testcacheduid, 生成预先缓存的UID

### 关于UID比特分配的建议

对于**并发数要求不高、期望长期使用**的应用，可增加```timeBits```位数, 减少```seqBits```位数。例如节点采取用完即弃的 WorkerIdAssigner 策略，重启频率为12次/天，那么配置成 `{"workerBits":23,"timeBits":31,"seqBits":9}` 时，可支持28个节点以整体并发量14400 UID/s的速度持续运行68年。

对于**节点重启频率频繁、期望长期使用**的应用, 可增加```workerBits```和```timeBits```位数, 减少```seqBits```位数. 例如节点采取用完即弃的WorkerIdAssigner策略, 重启频率为24*12次/天，那么配置成```{"workerBits":27,"timeBits":30,"seqBits":6}```时，可支持37个节点以整体并发量2400 UID/s的速度持续运行34年。

#### 吞吐量测试
在MacBook Pro（2.7GHz Intel Core i5, 8G DDR3）上进行了CachedUidGenerator（单实例）的UID吞吐量测试。

首先固定住workerBits为任选一个值(如20)，分别统计timeBits变化时(如从25至32，总时长分别对应1年和136年)的吞吐量，如下表所示:

|timeBits|25|26|27|28|29|30|31|32|
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
|throughput|6,831,465|7,007,279|6,679,625|6,499,205|6,534,971|7,617,440|6,186,930|6,364,997|

![throughput1](uid-generator/doc/throughput1.png)

再固定住timeBits为任选一个值(如31)，分别统计workerBits变化时(如从20至29，总重启次数分别对应1百万和500百万)的吞吐量，如下表所示：

|workerBits|20|21|22|23|24|25|26|27|28|29|
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
|throughput|6,186,930|6,642,727|6,581,661|6,462,726|6,774,609|6,414,906|6,806,266|6,223,617|6,438,055|6,435,549|

![throughput1](uid-generator/doc/throughput2.png)

由此可见, 不管如何配置, CachedUidGenerator总能提供**600万/s**的稳定吞吐量, 只是使用年限会有所减少。这真的是太棒了。

最后, 固定住workerBits和timeBits位数(如23和31), 分别统计不同数目(如1至8,本机CPU核数为4)的UID使用者情况下的吞吐量，

|workerBits|1|2|3|4|5|6|7|8|
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
|throughput|6,462,726|6,542,259|6,077,717|6,377,958|7,002,410|6,599,113|7,360,934|6,490,969|

![throughput1](uid-generator/doc/throughput3.png)
