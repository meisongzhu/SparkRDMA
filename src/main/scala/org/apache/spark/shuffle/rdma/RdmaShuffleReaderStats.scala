/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.rdma

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._

class RdmaRemoteFetchHistogram(numBuckets: Int, bucketSize: Int) {
  private val results = Array.fill(numBuckets + 1) { new AtomicInteger(0) }

  def addSample(sampleInMs: Int): Unit = {
    assume(sampleInMs >= 0)
    val bucket = sampleInMs / bucketSize
    if (bucket < numBuckets) {
      results(bucket).incrementAndGet
    } else { // sample is > max
      results(numBuckets).incrementAndGet
    }
  }

  def getString: String = {
    var str: String = ""
    for (i <- 0 until numBuckets) {
      str += ((i * bucketSize) + "-" + ((i + 1) * bucketSize) + "ms " + results(i) + ", ")
    }
    str += ((numBuckets * bucketSize) + "-...ms " + results(numBuckets))
    str
  }
}

class RdmaShuffleReaderStats(rdmaShuffleConf: RdmaShuffleConf) {
  private val logger = LoggerFactory.getLogger(classOf[RdmaShuffleReaderStats])
  private val remoteFetchHistogramMap =
    new ConcurrentHashMap[RdmaShuffleManagerId, RdmaRemoteFetchHistogram]()
  private val globalHistogram = new RdmaRemoteFetchHistogram(rdmaShuffleConf.fetchTimeNumBuckets,
    rdmaShuffleConf.fetchTimeBucketSizeInMs)

  def updateRemoteFetchHistogram(hostPort: RdmaShuffleManagerId, fetchTimeInMs: Int): Unit = {
    var remoteFetchHistogram = remoteFetchHistogramMap.get(hostPort)
    if (remoteFetchHistogram == null) {
      remoteFetchHistogram = new RdmaRemoteFetchHistogram(rdmaShuffleConf.fetchTimeNumBuckets,
        rdmaShuffleConf.fetchTimeBucketSizeInMs)
      val res = remoteFetchHistogramMap.putIfAbsent(hostPort, remoteFetchHistogram)
      if (res != null) {
        remoteFetchHistogram = res
      }
    }
    remoteFetchHistogram.addSample(fetchTimeInMs)
    globalHistogram.addSample(fetchTimeInMs)
  }

  def printRemoteFetchHistogram(): Unit = {
    remoteFetchHistogramMap.asScala.foreach{ case (hostPort, histogram) =>
      logger.info("Fetch blocks from " + hostPort.toString + ": " + histogram.getString)
    }
    logger.info("Total fetches from all remote hostPorts: " + globalHistogram.getString)
  }
}