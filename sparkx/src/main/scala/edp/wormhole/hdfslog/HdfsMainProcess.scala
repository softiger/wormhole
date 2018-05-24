/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.wormhole.hdfslog

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.util.UUID

import edp.wormhole.common.WormholeUtils.dataParse
import edp.wormhole.common._
import edp.wormhole.common.util.DateUtils
import edp.wormhole.kafka.WormholeKafkaProducer
import org.apache.spark.streaming.kafka010.CanCommitOffsets
import edp.wormhole.common.hadoop.HdfsUtils._
import edp.wormhole.common.util.DateUtils._
import edp.wormhole.sinks.utils.SinkCommonUtils._
import edp.wormhole.spark.log.EdpLogging
import edp.wormhole.ums.UmsSchemaUtils._
import edp.wormhole.ums._
import edp.wormhole.ums.UmsSysField._
import org.apache.hadoop.conf.Configuration
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.kafka010.{HasOffsetRanges, OffsetRange, WormholeDirectKafkaInputDStream}
import org.apache.spark.HashPartitioner

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.util.control.NonFatal

//fileName:  ../oracle.oracle0.db.table/1/0/0/data_increment_data(data_initial_data)/right(wrong)/740（文件编号）
//metaFile   ../oracle.oracle0.db.table/1/0/0/data_increment_data(data_initial_data)/right(wrong)/metadata_740
//metaContent  740_0_20171108181403252_20171106171538333_20171111171538333
//              文件编号_0/1(写完/未写完)_createtime_minUmsts_maxUmsts
object HdfsMainProcess extends EdpLogging {
  val namespace2FileStore = mutable.HashMap.empty[(String, String), mutable.HashMap[String, (String, Int, String)]]
  // Map[(protocoltype,namespace(accurate to table)), HashMap["right", (filename, size,metaContent)]]
  val directiveNamespaceRule = mutable.HashMap.empty[String, Int] //[namespace, hour]
  val jsonSourceMap = mutable.HashMap.empty[String, (Seq[FieldInfo], ArrayBuffer[(String, String)], Seq[UmsField])]
  //Map[namespace(7fields),(json schema info1, json schema info2,flat data)]

  val fileMaxSize = 128
  val metadata = "metadata_"
  val hdfsLog = "hdfslog/"

  def process(stream: WormholeDirectKafkaInputDStream[String, String], config: WormholeConfig): Unit = {
    val zookeeperPath = config.zookeeper_path
    stream.foreachRDD(foreachFunc = (streamRdd: RDD[ConsumerRecord[String, String]]) => {
      val offsetInfo: ArrayBuffer[OffsetRange] = new ArrayBuffer[OffsetRange]
      val batchId = UUID.randomUUID().toString
      try {

        val rddTs = System.currentTimeMillis
        if (SparkUtils.isLocalMode(config.spark_config.master)) logWarning("rdd count ===> " + streamRdd.count())
        val directiveTs = System.currentTimeMillis
        HdfsDirective.doDirectiveTopic(config, stream)
        streamRdd.asInstanceOf[HasOffsetRanges].offsetRanges.copyToBuffer(offsetInfo)

        val streamTransformedRdd: RDD[((String, String), String)] = streamRdd.map(message => {

          if (message.key == null || message.key.trim.isEmpty) {
            val namespace = WormholeUtils.getFieldContentFromJson(message.value,"namespace")
            val protocolType = WormholeUtils.getProtocolTypeFromUms(message.value)
            ((protocolType, namespace), message.value)
          } else {
            val (protocol, namespace) = WormholeUtils.getTypeNamespaceFromKafkaKey(message.key)
            ((protocol.toString, namespace), message.value)
          }
        })

        val dataParRdd = if (config.rdd_partition_number != -1) {
          streamTransformedRdd.partitionBy(new HashPartitioner(config.rdd_partition_number))
        } else streamTransformedRdd

        val namespace2FileMap: Map[(String, String), mutable.HashMap[String, (String, Int, String)]] = namespace2FileStore.toMap
        val validNameSpaceMap: Map[String, Int] = directiveNamespaceRule.toMap //validNamespaceMap is NOT real namespace, has *
        logInfo("validNameSpaceMap:" + validNameSpaceMap)
        val jsonInfoMap: Map[String, (Seq[FieldInfo], ArrayBuffer[(String, String)], Seq[UmsField])] = jsonSourceMap.toMap
        val mainDataTs = System.currentTimeMillis
        val partitionResultRdd = dataParRdd.mapPartitionsWithIndex { case (index, partition) =>
          // partition: ((protocol,namespace), message.value)
          val resultList = ListBuffer.empty[PartitionResult]
          val namespaceMap = mutable.HashMap.empty[(String, String), Int] //real namespace, do not have *
        val dataList = partition.toList
          dataList.foreach(data => {
            val result: Map[String, Int] = checkValidNamespace(data._1._2, validNameSpaceMap)
            if (result.nonEmpty && (data._1._1 == UmsProtocolType.DATA_INITIAL_DATA.toString || data._1._1 == UmsProtocolType.DATA_INCREMENT_DATA.toString)) {
              val (_, hour) = result.head
              if (!namespaceMap.contains((UmsProtocolType.DATA_INITIAL_DATA.toString, data._1._2)))
                namespaceMap((UmsProtocolType.DATA_INITIAL_DATA.toString, data._1._2)) = hour
              if (!namespaceMap.contains((UmsProtocolType.DATA_INCREMENT_DATA.toString, data._1._2)))
                namespaceMap((UmsProtocolType.DATA_INCREMENT_DATA.toString, data._1._2)) = hour
            }
          })
          logInfo("check namespace ok. all data num=" + dataList.size + ",namespaceMap=" + namespaceMap)

          namespaceMap.foreach { case ((protocol, namespace), hour) =>
            val namespaceDataList = ListBuffer.empty[String]
            dataList.foreach(data => {
              if (data._1._1 == protocol && data._1._2 == namespace) namespaceDataList.append(data._2)
            })
            logInfo("protocol=" + protocol + ",namespace=" + namespace + ",data num=" + namespaceDataList.size)
            var tmpMinTs = ""
            var tmpMaxTs = ""
            var tmpCount = 0
            try {
              if (namespaceDataList.nonEmpty) {
                val tmpResult: PartitionResult =
                  doMainData(protocol, namespace, namespaceDataList, config, hour, namespace2FileMap, zookeeperPath, jsonInfoMap, index)
                tmpMinTs = tmpResult.minTs
                tmpMaxTs = tmpResult.maxTs
                tmpCount = tmpResult.allCount
                resultList += tmpResult
              }
            } catch {
              case e: Throwable =>
                logAlert("batch error", e)
                WormholeKafkaProducer.sendMessage(config.kafka_output.feedback_topic_name, FeedbackPriority.FeedbackPriority3, UmsProtocolUtils.feedbackFlowError(namespace, config.spark_config.stream_id, currentDateTime, "", UmsWatermark(tmpMinTs), UmsWatermark(tmpMaxTs), tmpCount, "", batchId), None, config.kafka_output.brokers)
            }
          }
          resultList.toIterator
        }.cache

        partitionResultRdd.collect.foreach(eachResult => {
          if (!namespace2FileStore.contains((eachResult.protocol, eachResult.namespace)))
            namespace2FileStore((eachResult.protocol, eachResult.namespace)) = mutable.HashMap.empty[String, (String, Int, String)]
          if (eachResult.result && eachResult.allCount > 0 && eachResult.errorFileName != null) {
            namespace2FileStore((eachResult.protocol, eachResult.namespace))("wrong") = (eachResult.errorFileName, eachResult.errorCount, eachResult.errorMetaContent)
          }
          if (eachResult.result && eachResult.allCount > 0 && eachResult.correctFileName != null) {
            namespace2FileStore((eachResult.protocol, eachResult.namespace))("right") = (eachResult.correctFileName, eachResult.correctCount, eachResult.correctMetaContent)
          }
          val doneTs = System.currentTimeMillis
          if (eachResult.allCount > 0 && eachResult.maxTs != "")
            WormholeKafkaProducer.sendMessage(config.kafka_output.feedback_topic_name, FeedbackPriority.FeedbackPriority4,
              UmsProtocolUtils.feedbackFlowStats(eachResult.namespace, eachResult.protocol, currentDateTime, config.spark_config.stream_id, batchId, eachResult.namespace,
                eachResult.allCount, DateUtils.dt2date(eachResult.maxTs).getTime, rddTs, directiveTs, mainDataTs, mainDataTs, mainDataTs, doneTs), None, config.kafka_output.brokers)
        })
        partitionResultRdd.unpersist()
        WormholeUtils.sendTopicPartitionOffset(offsetInfo, config.kafka_output.feedback_topic_name, config, batchId)
      } catch {
        case e: Throwable =>
          logAlert("batch error", e)
          WormholeKafkaProducer.sendMessage(config.kafka_output.feedback_topic_name, FeedbackPriority.FeedbackPriority3, UmsProtocolUtils.feedbackStreamBatchError(config.spark_config.stream_id, currentDateTime, UmsFeedbackStatus.FAIL, e.getMessage, batchId), None, config.kafka_output.brokers)
          WormholeUtils.sendTopicPartitionOffset(offsetInfo, config.kafka_output.feedback_topic_name, config, batchId)
      }
      stream.asInstanceOf[CanCommitOffsets].commitAsync(offsetInfo.toArray)
    })
  }


  private def createFile(message: String, filePrefixShardingSlash: String, configuration: Configuration, minTs: String,
                         maxTs: String, zookeeperPath: String, index: Int): (String, String) = {

    //    val filePrefixShardingSlashSplit = filePrefixShardingSlash.split("/")
    //    val length = filePrefixShardingSlashSplit.length
    //    val nodePath = WormholeConstants.CheckpointRootPath + hdfsLog + filePrefixShardingSlashSplit.slice(length - 5, length).mkString("/")
    val processTime = currentyyyyMMddHHmmssmls
    val indexStr = "000" + index

    val incrementalId = currentyyyyMMddHHmmss + indexStr.substring(indexStr.length - 4, indexStr.length)
    //WormholeZkClient.getNextAtomicIncrement(zookeeperPath, nodePath)
    val metaName = if (minTs == null) filePrefixShardingSlash + "wrong" + "/" + "metadata_" + incrementalId else filePrefixShardingSlash + "right" + "/" + "metadata_" + incrementalId
    val metaContent: String = if (minTs == null) incrementalId + "_" + "0_" + processTime + "_" + processTime else incrementalId + "_" + "0_" + processTime + "_" + minTs + "_" + maxTs
    val dataName = if (minTs == null) filePrefixShardingSlash + "wrong" + "/" + incrementalId else filePrefixShardingSlash + "right" + "/" + incrementalId
    logInfo("dataName:" + dataName)
    logInfo("metaName:" + metaName)
    createPath(configuration, metaName)
    createPath(configuration, dataName)
    writeString(metaContent, metaName)
    (metaContent, dataName)
  }

  private def getMinMaxTs(message: String, namespace: String, jsonInfoMap: Map[String, (Seq[FieldInfo], ArrayBuffer[(String, String)], Seq[UmsField])]) = {
    var currentUmsTsMin: String = ""
    var currentUmsTsMax: String = ""
    if (jsonInfoMap.contains(namespace)) {
      val mapValue: (Seq[FieldInfo], ArrayBuffer[(String, String)], Seq[UmsField]) = jsonInfoMap(namespace)
      val value: Seq[UmsTuple] = dataParse(message, mapValue._1, mapValue._2)
      val schema = mapValue._3
      val umsTsIndex = schema.map(_.name).indexOf(TS.toString)
      value.foreach(tuple => {
        val umsTs = tuple.tuple(umsTsIndex)
        if (currentUmsTsMin == "") {
          currentUmsTsMin = umsTs
          currentUmsTsMax = umsTs
        } else {
          currentUmsTsMax = if (firstTimeAfterSecond(umsTs, currentUmsTsMax)) umsTs else currentUmsTsMax
          currentUmsTsMin = if (firstTimeAfterSecond(currentUmsTsMin, umsTs)) umsTs else currentUmsTsMin
        }
      })
    } else {
      val ums = toUms(message)
      var umsTsIndex: Int = -1
      ums.schema.fields.get.foreach(f => {
        if (f.name.toLowerCase == TS.toString) umsTsIndex = ums.schema.fields.get.indexOf(f)
      })

      ums.payload_get.foreach(tuple => {
        val umsTs = tuple.tuple(umsTsIndex)
        if (currentUmsTsMin == "") {
          currentUmsTsMin = umsTs
          currentUmsTsMax = umsTs
        } else {
          currentUmsTsMax = if (firstTimeAfterSecond(umsTs, currentUmsTsMax)) umsTs else currentUmsTsMax
          currentUmsTsMin = if (firstTimeAfterSecond(currentUmsTsMin, umsTs)) umsTs else currentUmsTsMin
        }
      })
    }
    (yyyyMMddHHmmssmls(currentUmsTsMin), yyyyMMddHHmmssmls(currentUmsTsMax))
  }


  private def writeAndCreateFile(currentMetaContent: String, fileName: String, configuration: Configuration, input: ByteArrayOutputStream,
                                 content: Array[Byte], message: String, minTs: String, maxTs: String, finalMinTs: String, finalMaxTs: String,
                                 splitMark: Array[Byte], zookeeperPath: String, index: Int) = {
    val metaName = getMetaName(fileName)
    setMetaDataFinished(metaName, currentMetaContent, configuration, minTs, finalMinTs, finalMaxTs)
    val bytes = input.toByteArray
    val in = new ByteArrayInputStream(bytes)
    appendToFile(configuration, fileName, in)
    input.reset()
    var currentSize = 0

    val slashPosition = fileName.lastIndexOf("/")
    val filePrefix = fileName.substring(0, slashPosition + 1)
    val filePrefixShardingSlash = filePrefix.substring(0, filePrefix.length - 6)
    val (newMeta, newFileName) = createFile(message, filePrefixShardingSlash, configuration, minTs, maxTs, zookeeperPath, index)
    currentSize += content.length + splitMark.length
    input.write(content)
    input.write(splitMark)
    (newFileName, newMeta, currentSize)
  }

  private def doMainData(protocol: String, namespace: String, dataList: Seq[String], config: WormholeConfig, hour: Int,
                         namespace2FileMap: Map[(String, String), mutable.HashMap[String, (String, Int, String)]],
                         zookeeperPath: String, jsonInfoMap: Map[String, (Seq[FieldInfo], ArrayBuffer[(String, String)], Seq[UmsField])], index: Int): PartitionResult = {
    var valid = true
    val namespaceSplit = namespace.split("\\.")
    val namespaceDb = namespaceSplit.slice(0, 3).mkString(".")
    val namespaceTable = namespaceSplit(3)
    val version = namespaceSplit(4)
    val sharding1 = namespaceSplit(5)
    val sharding2 = namespaceSplit(6)
    val filePrefixShardingSlash = config.stream_hdfs_address.get + "/" + "hdfslog" + "/" + namespaceDb.toLowerCase + "/" + namespaceTable.toLowerCase + "/" + version + "/" + sharding1 + "/" + sharding2 + "/" + protocol + "/"
    var correctFileName: String = if (namespace2FileMap.contains((protocol, namespace)) && namespace2FileMap((protocol, namespace)).contains("right"))
      namespace2FileMap((protocol, namespace))("right")._1 else null
    var correctCurrentSize: Int = if (namespace2FileMap.contains((protocol, namespace)) && namespace2FileMap((protocol, namespace)).contains("right"))
      namespace2FileMap((protocol, namespace))("right")._2 else 0
    var errorFileName: String = if (namespace2FileMap.contains((protocol, namespace)) && namespace2FileMap((protocol, namespace)).contains("wrong"))
      namespace2FileMap((protocol, namespace))("wrong")._1 else null
    var errorCurrentSize: Int = if (namespace2FileMap.contains((protocol, namespace)) && namespace2FileMap((protocol, namespace)).contains("wrong"))
      namespace2FileMap((protocol, namespace))("wrong")._2 else 0

    var currentErrorMetaContent: String = if (namespace2FileMap.contains((protocol, namespace)) && namespace2FileMap((protocol, namespace)).contains("wrong"))
      namespace2FileMap((protocol, namespace))("wrong")._3 else null
    var currentCorrectMetaContent: String = if (namespace2FileMap.contains((protocol, namespace)) && namespace2FileMap((protocol, namespace)).contains("right"))
      namespace2FileMap((protocol, namespace))("right")._3 else null

    var finalMinTs: String = if (currentCorrectMetaContent == null) "" else currentCorrectMetaContent.split("_")(3)
    var finalMaxTs: String = if (currentCorrectMetaContent == null) "" else currentCorrectMetaContent.split("_")(4)
    val count = dataList.size
    val inputCorrect = new ByteArrayOutputStream()
    val inputError = new ByteArrayOutputStream()
    try {
      val configuration = new Configuration()
      val hdfsPath = config.stream_hdfs_address.get
      val hdfsPathGrp = hdfsPath.split("//")
      val hdfsRoot = if (hdfsPathGrp(1).contains("/")) hdfsPathGrp(0) + "//" + hdfsPathGrp(1).substring(0, hdfsPathGrp(1).indexOf("/")) else hdfsPathGrp(0) + "//" + hdfsPathGrp(1)
      configuration.set("fs.defaultFS", hdfsRoot)
      configuration.setBoolean("fs.hdfs.impl.disable.cache", true)

      dataList.foreach(data => {
        val splitMark = "\n".getBytes()
        val splitMarkLength = splitMark.length
        var minTs: String = null
        var maxTs: String = null
        try {
          val timePair = getMinMaxTs(data, namespace, jsonInfoMap)
          minTs = timePair._1
          maxTs = timePair._2
        } catch {
          case NonFatal(e) => logError(s"message convert failed:\n$data", e)
        }

        if (minTs == null && errorFileName == null) {
          val (meta, name) = createFile(data, filePrefixShardingSlash, configuration, minTs, maxTs, zookeeperPath, index)
          errorFileName = name
          currentErrorMetaContent = meta
          errorCurrentSize = 0
        }

        if (minTs == null && errorFileName != null) {
          val errorMetaName = getMetaName(errorFileName)
          val metaContent = currentErrorMetaContent
          val metaContentSplit = metaContent.split("_")
          val length = metaContentSplit.length
          val originalProcessTime = metaContentSplit(length - 2)
          if (dt2timestamp(dt2dateTime(originalProcessTime).plusHours(hour)).compareTo(dt2timestamp(dt2dateTime(currentyyyyMMddHHmmssmls))) < 0) {
            setMetaDataFinished(errorMetaName, metaContent, configuration, minTs, finalMinTs, finalMaxTs)
            val (meta, name) = createFile(data, filePrefixShardingSlash, configuration, minTs, maxTs, zookeeperPath, index)
            errorFileName = name
            currentErrorMetaContent = meta
            errorCurrentSize = 0
          }
        }

        if (minTs != null && correctFileName == null) {
          val (meta, name) = createFile(data, filePrefixShardingSlash, configuration, minTs, maxTs, zookeeperPath, index)
          correctFileName = name
          currentCorrectMetaContent = meta
          correctCurrentSize = 0
          finalMinTs = minTs
          finalMaxTs = maxTs
        }

        if (minTs != null && correctFileName != null) {
          val correctMetaName = getMetaName(correctFileName)
          val metaContent = currentCorrectMetaContent
          val metaContentSplit = metaContent.split("_")
          val length = metaContentSplit.length
          val originalProcessTime = metaContentSplit(length - 3)
          if (dt2timestamp(dt2dateTime(originalProcessTime).plusHours(hour)).compareTo(dt2timestamp(dt2dateTime(currentyyyyMMddHHmmssmls))) < 0) {
            setMetaDataFinished(correctMetaName, metaContent, configuration, minTs, finalMinTs, finalMaxTs)
            val (meta, name) = createFile(data, filePrefixShardingSlash, configuration, minTs, maxTs, zookeeperPath, index)
            correctFileName = name
            currentCorrectMetaContent = meta
            correctCurrentSize = 0
            finalMinTs = minTs
            finalMaxTs = maxTs
          }
        }

        val content = data.getBytes(StandardCharsets.UTF_8)

        if (minTs == null) {
          if (errorCurrentSize + content.length + splitMarkLength < fileMaxSize * 1024 * 1024) { //60 * 1024 * 1024 Bytes = 60MB
            errorCurrentSize += content.length + splitMarkLength
            inputError.write(content)
            inputError.write(splitMark)
            val indexLastUnderScore = currentErrorMetaContent.lastIndexOf("_")
            currentErrorMetaContent = currentErrorMetaContent.substring(0, indexLastUnderScore + 1) + currentyyyyMMddHHmmssmls
          } else {
            val errorTuple = writeAndCreateFile(currentErrorMetaContent, errorFileName, configuration, inputError,
              content, data, minTs, maxTs, finalMinTs, finalMaxTs, splitMark, zookeeperPath, index)
            errorFileName = errorTuple._1
            currentErrorMetaContent = errorTuple._2
            errorCurrentSize = errorTuple._3
          }
        } else {
          if (correctCurrentSize + content.length + splitMarkLength < fileMaxSize * 1024 * 1024) {
            correctCurrentSize += content.length + splitMarkLength
            inputCorrect.write(content)
            inputCorrect.write(splitMark)
            finalMinTs = if (finalMinTs.isEmpty || firstTimeAfterSecond(finalMinTs, minTs)) minTs else finalMinTs
            finalMaxTs = if (finalMaxTs.isEmpty || firstTimeAfterSecond(maxTs, finalMaxTs)) maxTs else finalMaxTs
            val currentCorrectMetaContentSplit = currentCorrectMetaContent.split("_")
            currentCorrectMetaContent = currentCorrectMetaContentSplit(0) + "_0_" + currentCorrectMetaContentSplit(2) + "_" + finalMinTs + "_" + finalMaxTs
          } else {
            val correctTuple = writeAndCreateFile(currentCorrectMetaContent, correctFileName, configuration, inputCorrect, content,
              data, minTs, maxTs, finalMinTs, finalMaxTs, splitMark, zookeeperPath, index)
            correctFileName = correctTuple._1
            currentCorrectMetaContent = correctTuple._2
            correctCurrentSize = correctTuple._3
            finalMinTs = minTs
            finalMaxTs = maxTs
          }
        }
      })
      val bytesError = inputError.toByteArray
      val bytesCorrect = inputCorrect.toByteArray
      val inError = new ByteArrayInputStream(bytesError)
      val inCorrect = new ByteArrayInputStream(bytesCorrect)
      if (bytesError.nonEmpty) {
        appendToFile(configuration, errorFileName, inError)
        val errorMetaName = getMetaName(errorFileName)
        updateMeta(errorMetaName, currentErrorMetaContent, configuration)
      }
      if (bytesCorrect.nonEmpty) {
        appendToFile(configuration, correctFileName, inCorrect)
        val correctMetaName = getMetaName(correctFileName)
        updateMeta(correctMetaName, currentCorrectMetaContent, configuration)
      }
    } catch {
      case e: Throwable =>
        logAlert("batch error", e)
        valid = false
    } finally {
      try {
        if (inputError != null)
          inputError.close()
        if (inputCorrect != null)
          inputCorrect.close()
      } catch {
        case e: Throwable =>
          logWarning("close", e)
      }
    }

    PartitionResult(valid, errorFileName, errorCurrentSize, currentErrorMetaContent, correctFileName, correctCurrentSize, currentCorrectMetaContent, protocol, namespace, finalMinTs, finalMaxTs, count)
  }

  def setMetaDataFinished(metaName: String, currentMetaContent: String, configuration: Configuration, minTs: String, finalMinTs: String, finalMaxTs: String): Unit = {
    var newMetaContent: String = null
    val splitCurrentMetaContent = currentMetaContent.split("_")
    if (minTs != null) {
      newMetaContent = splitCurrentMetaContent(0) + "_1_" + splitCurrentMetaContent(2) + "_" + finalMinTs + "_" + finalMaxTs
    } else {
      newMetaContent = splitCurrentMetaContent(0) + "_1_" + splitCurrentMetaContent(2) + "_" + currentyyyyMMddHHmmssmls
    }
    writeString(newMetaContent, metaName)
  }

  def updateMeta(metaName: String, metaContent: String, configuration: Configuration): Unit = {
    writeString(metaContent, metaName)
  }

  def getMetaName(fileName: String): String = {
    val incrementalId = fileName.substring(fileName.lastIndexOf("/") + 1).trim
    fileName.substring(0, fileName.lastIndexOf("/") + 1) + metadata + incrementalId
  }


  def matchNameSpace(rule: String, namespace: String): Boolean = {
    val regex = rule.replace(".", "\\.") replace("*", ".*")
    namespace.matches(regex)
  }


  def checkValidNamespace(namespace: String, validNameSpaceMap: Map[String, Int]): Map[String, Int] = {
    validNameSpaceMap.filter { case (rule, _) =>
      val namespaceLowerCase = namespace.toLowerCase
      matchNameSpace(rule, namespaceLowerCase)
    }
  }
}


