/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in 
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */


package com.tencent.angel.ml.core.graphsubmit

import com.tencent.angel.client.AngelClient
import com.tencent.angel.ml.core.conf.{MLConf, SharedConf}
import com.tencent.angel.ml.core.network.layers.verge.{Embedding, SimpleInputLayer}
import com.tencent.angel.ml.core.network.layers.{AngelGraph, PlaceHolder}
import com.tencent.angel.ml.core.optimizer.loss._
import com.tencent.angel.ml.core.utils.paramsutils.{JsonUtils, ParamKeys}
import com.tencent.angel.ml.feature.LabeledData
import com.tencent.angel.ml.math2.matrix.{BlasDoubleMatrix, BlasFloatMatrix}
import com.tencent.angel.ml.model.MLModel
import com.tencent.angel.ml.predict.PredictResult
import com.tencent.angel.utils.HdfsUtil
import com.tencent.angel.worker.storage.{DataBlock, MemoryDataBlock}
import com.tencent.angel.worker.task.TaskContext
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataOutputStream, FileSystem, Path}
import org.json4s.JValue
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods.{pretty, render}

class GraphModel(conf: Configuration, _ctx: TaskContext = null)
  extends MLModel(conf, _ctx) {
  val sharedConf: SharedConf = SharedConf.get()
  implicit lazy val graph: AngelGraph = new AngelGraph(new PlaceHolder(sharedConf), sharedConf)

  val batchSize: Int = SharedConf.batchSize
  val blockSize: Int = SharedConf.blockSize
  val dataFormat: String = SharedConf.inputDataFormat
  var jsonAst: JValue = _

  def ensureJsonAst(): Unit = {
    if (sharedConf.getJson == null) {
      JsonUtils.init()
    }
    jsonAst = sharedConf.getJson
  }

  def lossFunc: LossFunc = {
    ensureJsonAst()
    JsonUtils.getLossFunc(jsonAst).build()
  }

  def buildNetwork(): Unit = {
    ensureJsonAst()
    JsonUtils.fillGraph(jsonAst)
  }

  /**
    * Predict use the PSModels and predict data
    *
    * @param storage predict data
    * @return predict result
    */
  override def predict(storage: DataBlock[LabeledData]): DataBlock[PredictResult] = {
    val resData = new MemoryDataBlock[PredictResult](storage.size())
    var pullFlag = false

    val batchData = new Array[LabeledData](batchSize)
    (0 until storage.size()).foreach { i =>
      if (i != 0 && i % batchSize == 0) {
        graph.feedData(batchData)
        if (!pullFlag) {
          graph.pullParams(1)
          pullFlag = true
        } else {
          graph.getTrainable.foreach {
            case layer: Embedding =>
              layer.pullParams(1)
            case layer: SimpleInputLayer =>
              layer.pullParams(1)
            case _ =>
          }
        }


        val attached = graph.placeHolder.getAttached
        (graph.predict(), graph.getLossLayer.getLossFunc()) match {
          case (mat: BlasDoubleMatrix, lossFunc: SoftmaxLoss) if mat.getNumCols == 4 =>
            (0 until mat.getNumRows).foreach { i =>
              resData.put(SoftmaxPredictResult(attached(i), mat.get(i, 0), mat.get(i, 1), mat.get(i, 2), mat.get(i, 3)))
            }
          case (mat: BlasFloatMatrix, lossFunc: SoftmaxLoss) if mat.getNumCols == 4 =>
            (0 until mat.getNumRows).foreach { i =>
              resData.put(SoftmaxPredictResult(attached(i), mat.get(i, 0), mat.get(i, 1), mat.get(i, 2), mat.get(i, 3)))
            }
          case (mat: BlasDoubleMatrix, _) =>
            (0 until mat.getNumRows).foreach { i =>
              resData.put(GraphPredictResult(attached(i), mat.get(i, 0), mat.get(i, 1), mat.get(i, 2)))
            }
          case (mat: BlasFloatMatrix, _) =>
            (0 until mat.getNumRows).foreach { i =>
              resData.put(GraphPredictResult(attached(i), mat.get(i, 0), mat.get(i, 1), mat.get(i, 2)))
            }
        }
      }

      batchData(i % batchSize) = storage.loopingRead()
    }

    var left = storage.size() % batchSize
    if (left == 0 && storage.size() > 0) {
      left = batchSize
    }
    if (left != 0) {
      val leftData = new Array[LabeledData](left)
      Array.copy(batchData, 0, leftData, 0, left)
      graph.feedData(leftData)
      graph.getTrainable.foreach {
        case layer: Embedding =>
          layer.pullParams(1)
        case _ =>
      }

      val attached = graph.placeHolder.getAttached
      (graph.predict(), graph.getLossLayer.getLossFunc()) match {
        case (mat: BlasDoubleMatrix, _: SoftmaxLoss) if mat.getNumCols == 4 =>
          (0 until mat.getNumRows).foreach { i =>
            resData.put(SoftmaxPredictResult(attached(i), mat.get(i, 0), mat.get(i, 1), mat.get(i, 2), mat.get(i, 3)))
          }
        case (mat: BlasFloatMatrix, _: SoftmaxLoss) if mat.getNumCols == 4 =>
          (0 until mat.getNumRows).foreach { i =>
            resData.put(SoftmaxPredictResult(attached(i), mat.get(i, 0), mat.get(i, 1), mat.get(i, 2), mat.get(i, 3)))
          }
        case (mat: BlasDoubleMatrix, _) =>
          (0 until mat.getNumRows).foreach { i =>
            resData.put(GraphPredictResult(attached(i), mat.get(i, 0), mat.get(i, 1), mat.get(i, 2)))
          }
        case (mat: BlasFloatMatrix, _) =>
          (0 until mat.getNumRows).foreach { i =>
            resData.put(GraphPredictResult(attached(i), mat.get(i, 0), mat.get(i, 1), mat.get(i, 2)))
          }
      }
    }
    resData
  }

  def init(taskflag: Int): Unit = {
    graph.init(taskflag)
  }

  def createMatrices(client: AngelClient): Unit = {
    graph.createMatrices(client)
  }

  def loadModel(client: AngelClient, path: String): Unit = {
    graph.loadModel(client, path)
  }

  def saveModel(client: AngelClient, path: String): Unit = {
    graph.saveModel(client, path)
  }

  def saveJson(path: String): Unit = {
    val jsonFile: Path = new Path(path, "graph.json")
    val tmpJsonFile = HdfsUtil.toTmpPath(jsonFile)
    val fs: FileSystem = tmpJsonFile.getFileSystem(conf)
    val jsonOut: FSDataOutputStream = fs.create(tmpJsonFile)
    jsonOut.writeBytes(pretty(render(toJson)))
    jsonOut.flush()
    jsonOut.close()
    HdfsUtil.rename(tmpJsonFile, jsonFile, fs)
  }

  def toJson: JObject = {
//    if (jsonAst != null) {
//      jsonAst.asInstanceOf[JObject]
//    } else {
      val data = (ParamKeys.format -> JString(SharedConf.inputDataFormat)) ~
        (ParamKeys.indexRange -> JLong(SharedConf.indexRange)) ~
        (ParamKeys.numField -> JInt(sharedConf.getInt(MLConf.ML_FIELD_NUM))) ~
        (ParamKeys.validateRatio -> JDouble(sharedConf.getDouble(MLConf.ML_VALIDATE_RATIO))) ~
        (ParamKeys.sampleRatio -> JDouble(sharedConf.getDouble(MLConf.ML_BATCH_SAMPLE_RATIO))) ~
        (ParamKeys.useShuffle -> JBool(sharedConf.getBoolean(MLConf.ML_DATA_USE_SHUFFLE))) ~
        (ParamKeys.posnegRatio -> JDouble(sharedConf.getDouble(MLConf.ML_DATA_POSNEG_RATIO))) ~
        (ParamKeys.transLabel -> JString(sharedConf.getString(MLConf.ML_DATA_LABEL_TRANS)))

      val model = (ParamKeys.modelType -> JString(SharedConf.modelType.toString)) ~
        (ParamKeys.modelSize -> JLong(SharedConf.modelSize)) ~
        (ParamKeys.blockSize -> JInt(sharedConf.getInt(MLConf.ML_BLOCK_SIZE)))

      val train = (ParamKeys.epoch -> JInt(SharedConf.epochNum)) ~
        (ParamKeys.numUpdatePerEpoch -> JInt(SharedConf.numUpdatePerEpoch)) ~
        (ParamKeys.batchSize -> JInt(SharedConf.batchSize)) ~
        (ParamKeys.lr -> JDouble(sharedConf.getDouble(MLConf.ML_LEARN_RATE))) ~
        (ParamKeys.decayClass -> JString(sharedConf.getString(MLConf.ML_OPT_DECAY_CLASS_NAME))) ~
        (ParamKeys.decayAlpha -> JDouble(sharedConf.getDouble(MLConf.ML_OPT_DECAY_ALPHA))) ~
        (ParamKeys.decayBeta -> JDouble(sharedConf.getDouble(MLConf.ML_OPT_DECAY_BETA)))

      (ParamKeys.data -> data) ~
      (ParamKeys.model -> model) ~
      (ParamKeys.train -> train) ~
        (ParamKeys.layers -> graph.toJson)
    //}
  }
}


object GraphModel {
  def apply(className: String, conf: Configuration): GraphModel = {
    val cls = Class.forName(className)
    val cstr = cls.getConstructor(classOf[Configuration], classOf[TaskContext])
    cstr.newInstance(conf, null).asInstanceOf[GraphModel]
  }

  def apply(className: String, conf: Configuration, ctx: TaskContext = null): GraphModel = {
    val cls = Class.forName(className)
    val cstr = cls.getConstructor(classOf[Configuration], classOf[TaskContext])
    cstr.newInstance(conf, ctx).asInstanceOf[GraphModel]
  }
}
