[![Gitter chat](https://badges.gitter.im/emc-mongoose.png)](https://gitter.im/emc-mongoose)
[![Issue Tracker](https://img.shields.io/badge/Issue-Tracker-red.svg)](https://mongoose-issues.atlassian.net/projects/PULSAR)
[![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-pulsar/badges/master/pipeline.svg)](https://travis-ci.org/emc-mongoose/mongoose-storage-driver-pravega/builds)
[![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-pulsar/maven-metadata.xml.svg)](http://central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-pulsar)
[![Docker Pulls](https://img.shields.io/docker/pulls/emcmongoose/mongoose-storage-driver-pulsar.svg)](https://hub.docker.com/r/emcmongoose/mongoose-storage-driver-pulsar/)

Apache Pulsar extension for Mongoose

# Content

1. [Introduction](#1-introduction)<br/>
2. [Features](#2-features)<br/>
3. [Deployment](#3-deployment)<br/>
&nbsp;&nbsp;3.1. [Basic](#31-basic)<br/>
&nbsp;&nbsp;3.2. [Docker](#32-docker)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;3.2.1. [Standalone](#321-standalone)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;3.2.2. [Distributed](#322-distributed)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;3.2.2.1. [Additional Node](#3221-additional-node)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;3.2.2.2. [Entry Node](#3222-entry-node)<br/>
4. [Configuration](#4-configuration)<br/>
&nbsp;&nbsp;4.1. [Specific Options](#41-specific-options)<br/>
&nbsp;&nbsp;4.2. [Tuning](#42-tuning)<br/>
5. [Usage](#5-usage)<br/>
&nbsp;&nbsp;5.1. [Message Operations](#51-message-operations)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;5.1.1. [Create](#511-create)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;5.1.2. [Read](#512-read)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;5.1.3. [End-to-end Latency](#513-end-to-end-latency)<br/>
&nbsp;&nbsp;5.2. [Topic Operations](#52-topic-operations)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;5.2.1. [Create](#521-create)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;5.2.2. [Read](#522-read)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;5.2.3. [Update](#523-update)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;5.2.4. [Delete](#524-delete)<br/>
6. [Open Issues](#6-open-issues)<br/>
7. [Development](#7-development)<br/>
&nbsp;&nbsp;7.1. [Build](#71-build)<br/>
&nbsp;&nbsp;7.2. [Test](#72-test)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;7.2.1. [Automated](#721-automated)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;7.2.1.1. [Unit](#7211-unit)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;7.2.1.2. [Integration](#7212-integration)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;7.2.1.3. [Functional](#7213-functional)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;7.2.2. [Manual](#722-manual)<br/>

# 1. Introduction

| Pulsar | [Mongoose](https://github.com/emc-mongoose/mongoose-base/tree/master/doc/design/architecture#1-basic-terms) |
|---------|----------|
| Message | *Data Item* |
| Topic | *Item Path* or *Data Item* |

# 2. Features

* Authentication: https://mongoose-issues.atlassian.net/browse/PULSAR-4
* SSL/TLS: https://mongoose-issues.atlassian.net/browse/PULSAR-1
* Item Types:
    * `data`: corresponds to a ***message*** 
    * `path`: corresponds to a ***topic***
    * `token`: not supported
* Supported load operations:
    * `create` (messages)
    * `read` (messages)
    * `updae` (topics appending, TODO)
    * `delete` (topics, TODO)
* Storage-specific:
    * [End-to-end latency measurement](#513-end-to-end-latency)

# 3. Deployment

## 3.1. Basic

Java 11+ is required to build/run.

1. Get the latest `mongoose-base` jar from the 
[maven repo](http://repo.maven.apache.org/maven2/com/github/emc-mongoose/mongoose-base/)
and put it to your working directory. Note the particular version, which is referred as *BASE_VERSION* below.

2. Get the latest `mongoose-storage-driver-coop` jar from the
[maven repo](http://repo.maven.apache.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-coop/)
and put it to the `~/.mongoose/<BASE_VERSION>/ext` directory.

3. Get the latest `mongoose-storage-driver-pulsar` jar from the
[maven repo](http://repo.maven.apache.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-pulsar/)
and put it to the `~/.mongoose/<BASE_VERSION>/ext` directory.

```bash
java -jar mongoose-base-<BASE_VERSION>.jar \
    --storage-driver-type=pulsar \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --storage-net-node-port=6650 \
    --load-batch-size=1000 \
    --storage-driver-limit-concurrency=1000 \
    ...
```

## 3.2. Docker

### 3.2.1. Standalone

```bash
docker run \
    --network host \
    emcmongoose/mongoose-storage-driver-pulsar \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --load-batch-size=1000 \
    --storage-driver-limit-concurrency=1000 \
    ...
```

### 3.2.2. Distributed

#### 3.2.2.1. Additional Node

```bash
docker run \
    --network host \
    --expose 1099 \
    emcmongoose/mongoose-storage-driver-pulsar \
    --run-node
```

#### 3.2.2.2. Entry Node

```bash
docker run \
    --network host \
    emcmongoose/mongoose-storage-driver-pulsar \
    --load-step-node-addrs=<ADDR1,ADDR2,...> \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --load-batch-size=1000 \
    --storage-driver-limit-concurrency=1000 \
    ...
```

# 4. Configuration

Reference

## 4.1. Specific Options

| Name                               | Type            | Default Value | Description                                      |
|:-----------------------------------|:----------------|:--------------|:-------------------------------------------------|
| storage-driver-create-compression  | enum            | `none`        | Should compress data upon messages create or not (default). The available compression types are defined by the [Pulsar client](https://github.com/apache/pulsar/blob/be6a102511560349701dbe6b83fabf831dc81340/pulsar-client-api/src/main/java/org/apache/pulsar/client/api/CompressionType.java#L24)
| storage-driver-read-tail           | boolean         | `false`       | Should read the latest message of the topic or read from the topic beginning (default)
| storage-net-tcpNoDelay             | boolean         | `true`        | The option specifies whether the server disables the delay of sending successive small packets on the network.
| storage-net-timeoutMilliSec        | integer         | `0`           | Connection timeout. `0` means no timeout
| storage-net-node-addrs             | list of strings | `127.0.0.1`   | The list of the storage node IPs or hostnames to use for HTTP load. May include port numbers.
| storage-net-node-port              | integer         | `6650`        | The common port number to access the storage nodes, may be overriden adding the port number to the storage-driver-addrs, for example: "127.0.0.1:6650,127.0.0.1:6651,..."

## 4.2. Tuning

* `load-batch-size`
Determines how many operations the driver will try to submit at once. Large values may increase both the throughput and 
the memory consumption. 

* `storage-driver-concurrency-limit`
Determines how many operations may be in flight at every moment of the time. Specifying `0` will cause the "*burst 
mode*", when the driver submits as many operations as possible regardless their completion. The unlimited concurrency
is useful for the sustained rate measurement but may cause either Pulsar or Mongoose inconsistent state.

* `storage-driver-threads`
Determines the Pulsar client IO worker threads count.

# 5. Usage

## 5.1. Message Operations

### 5.1.1. Create

Example, write 1KB messages to the topic "topic1" in the Pulsar instance w/ address 12.34.56.78: 
```bash
docker run \
    --network host \
    emcmongoose/mongoose-storage-driver-pulsar \
    --storage-net-node-addrs=12.34.56.78 \
    --load-batch-size=1000 \
    --storage-driver-limit-concurrency=1000 \
    --item-data-size=1KB \
    --item-output-path=topic1
```

### 5.1.2. Read

#### 5.1.2.1. Basic

Example, read 1M messages from the beginning of the topic "topic1":
```bash
docker run \
    --network host \
    emcmongoose/mongoose-storage-driver-pulsar \
    --storage-net-node-addrs=12.34.56.78 \
    --load-batch-size=100 \
    --storage-driver-limit-concurrency=100 \
    --read \ 
    --item-input-path=topic1 \
    --load-op-recycle \
    --load-op-limit-count=1000000
```

**Note**: Mongoose couldn't determine the end of the topic(s), so it's mandatory to specify the count/time limit.

#### 5.1.2.2. Tail

Example, read all new messages from the topic "topic1" during the 1 minute:
```bash
docker run \
    --network host \
    emcmongoose/mongoose-storage-driver-pulsar \
    --storage-net-node-addrs=12.34.56.78 \
    --load-batch-size=100 \
    --storage-driver-limit-concurrency=100 \
    --read \ 
    --item-input-path=topic1 \
    --storage-driver-read-tail \
    --load-op-recycle \
    --load-step-limit-time=1m
```

**Note**: Mongoose couldn't determine the end of the topic(s), so it's mandatory to specify the count/time limit.

### 5.1.3. End-To-End Latency

TODO: https://mongoose-issues.atlassian.net/browse/PULSAR-3

## 5.2. Topic Operations

TODO: https://mongoose-issues.atlassian.net/browse/PULSAR-2

### 5.2.1. Create

TODO

### 5.2.2. Read

TODO

### 5.2.3. Update

TODO

### 5.2.4. Delete

TODO

# 6. Open Issues

Please refer to the [issue tracker](https://mongoose-issues.atlassian.net/projects/PULSAR)
# 7. Development

## 7.1. Build

```bash
./gradlew clean jar
```

## 7.2. Test

### 7.2.1. Automated

#### 7.2.1.1. Unit

```bash
./gradlew clean test
```

#### 7.2.1.2. Integration
```bash
docker run -it \
    -p 6650:6650 \
    -p 8080:8080 \
    apachepulsar/pulsar:<PULSAR_VERSION> \
    bin/pulsar standalone
./gradlew integrationTest
```

#### 7.2.1.3. Functional
```bash
./gradlew jar
export SUITE=api.storage
TEST=<TODO_TEST_NAME> ./gradlew robotest
```

### 7.2.1. Manual

1. [Build the storage driver](#71-build)
2. Copy the storage driver's jar file into the mongoose's `ext` directory:
```bash
cp -f build/libs/mongoose-storage-driver-pulsar-*.jar ~/.mongoose/<MONGOOSE_BASE_VERSION>/ext/
```
Note that the Pulsar storage driver depends on the 
[Coop Storage Driver](http://repo.maven.apache.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-coop/) 
extension so it should be also put into the `ext` directory
3. Install and run the Apache Pulsar
```bash
docker run -it \
    -p 6650:6650 \
    -p 8080:8080 \
    apachepulsar/pulsar:<PULSAR_VERSION> \
    bin/pulsar standalone
```
4. Run Mongoose's default scenario with some specific command-line arguments:
```bash
java -jar mongoose-<MONGOOSE_BASE_VERSION>.jar \
    --storage-driver-type=pulsar \
    --storage-net-node-port=6650 \
    --storage-driver-limit-concurrency=10 \
    --item-output-path=topic-0
```
