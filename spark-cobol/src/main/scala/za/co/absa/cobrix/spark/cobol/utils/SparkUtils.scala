/*
 * Copyright 2018 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.cobrix.spark.cobol.utils

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.spark.SparkContext
import org.apache.spark.sql.functions.{concat_ws, expr, max}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Column, DataFrame}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
  * This object contains common Spark tools used for easier processing of dataframes originated from mainframes.
  */
object SparkUtils {

  private val logger = LoggerFactory.getLogger(this.getClass)

  /**
    * Retrieves all executors available for the current job.
    */
  def currentActiveExecutors(sc: SparkContext): Seq[String] = {
    val allExecutors = sc.getExecutorMemoryStatus.map(_._1.split(":").head)
    val driverHost: String = sc.getConf.get("spark.driver.host", "localhost")

    logger.info(s"Going to filter driver from available executors: Driver host: $driverHost, Available executors: $allExecutors")

    allExecutors.filter(!_.equals(driverHost)).toList.distinct
  }

  /**
    * Given an instance of DataFrame returns a dataframe with flattened schema.
    * All nested structures are flattened and arrays are projected as columns.
    *
    * Note. The method checks the maximum size for each array and that could perform slowly,
    * especially on a vary big dataframes.
    *
    * @param df                 A dataframe
    * @param useShortFieldNames When flattening a schema each field name will contain full path. You can override this
    *                           behavior and use a short field names instead
    * @return A new dataframe with flat schema.
    */
  def flattenSchema(df: DataFrame, useShortFieldNames: Boolean = false): DataFrame = {
    val fields = new mutable.ListBuffer[Column]()
    val stringFields = new mutable.ListBuffer[String]()
    val usedNames = new mutable.HashSet[String]()

    def getNewFieldName(desiredName: String): String = {
      var name = desiredName
      var i = 1
      while (usedNames.contains(name)) {
        name = s"$desiredName$i"
        i += 1
      }
      usedNames.add(name)
      name
    }

    /**
      * Aggregating arrays of primitives by projecting it's columns
      *
      * @param path            path to an StructArray
      * @param fieldNamePrefix Prefix for the field name
      * @param structField     StructField
      * @param arrayType       ArrayType
      */
    def flattenStructArray(path: String, fieldNamePrefix: String, structField: StructField, arrayType: ArrayType): Unit = {
      val maxInd = getMaxArraySize(s"$path${structField.name}")
      var i = 0
      while (i < maxInd) {
        arrayType.elementType match {
          case st: StructType =>
            val newFieldNamePrefix = s"${fieldNamePrefix}${i}_"
            flattenGroup(s"$path`${structField.name}`[$i].", newFieldNamePrefix, st)
          case ar: ArrayType =>
            val newFieldNamePrefix = s"${fieldNamePrefix}${i}_"
            flattenArray(s"$path`${structField.name}`[$i].", newFieldNamePrefix, structField, ar)
          // AtomicType is protected on package 'sql' level so have to enumerate all subtypes :(
          case _ =>
            val newFieldNamePrefix = s"${fieldNamePrefix}${i}"
            val newFieldName = getNewFieldName(s"$newFieldNamePrefix")
            fields += expr(s"$path`${structField.name}`[$i]").as(newFieldName)
            stringFields += s"""expr("$path`${structField.name}`[$i] AS `$newFieldName`")"""
        }
        i += 1
      }
    }

    def flattenNestedArrays(path: String, fieldNamePrefix: String, arrayType: ArrayType): Unit = {
      val maxInd = getMaxArraySize(path)
      var i = 0
      while (i < maxInd) {
        arrayType.elementType match {
          case st: StructType =>
            val newFieldNamePrefix = s"${fieldNamePrefix}${i}_"
            flattenGroup(s"$path[$i]", newFieldNamePrefix, st)
          case ar: ArrayType =>
            val newFieldNamePrefix = s"${fieldNamePrefix}${i}_"
            flattenNestedArrays(s"$path[$i]", newFieldNamePrefix, ar)
          // AtomicType is protected on package 'sql' level so have to enumerate all subtypes :(
          case _ =>
            val newFieldNamePrefix = s"${fieldNamePrefix}${i}"
            val newFieldName = getNewFieldName(s"$newFieldNamePrefix")
            fields += expr(s"$path[$i]").as(newFieldName)
            stringFields += s"""expr("$path`[$i] AS `$newFieldName`")"""
        }
        i += 1
      }
    }

    def getMaxArraySize(path: String): Int = {
      getField(path, df.schema) match {
        case Some(field) if field.metadata.contains("maxElements") =>
          field.metadata.getLong("maxElements").toInt
        case _ =>
          val collected = df.agg(max(expr(s"size($path)"))).collect()(0)(0)
          if (collected != null) {
            // can be null for empty dataframe
            collected.toString.toInt
          } else {
            1
          }
      }
    }

    def flattenArray(path: String, fieldNamePrefix: String, structField: StructField, arrayType: ArrayType): Unit = {
      arrayType.elementType match {
        case _: ArrayType =>
          flattenNestedArrays(s"$path${structField.name}", fieldNamePrefix, arrayType)
        case _ =>
          flattenStructArray(path, fieldNamePrefix, structField, arrayType)
      }
    }

    def flattenGroup(path: String, fieldNamePrefix: String, structField: StructType): Unit = {
      structField.foreach(field => {
        val newFieldNamePrefix = if (useShortFieldNames) {
          s"${field.name}_"
        } else {
          s"$fieldNamePrefix${field.name}_"
        }
        field.dataType match {
          case st: StructType =>
            flattenGroup(s"$path`${field.name}`.", newFieldNamePrefix, st)
          case arr: ArrayType =>
            flattenArray(path, newFieldNamePrefix, field, arr)
          case _ =>
            val newFieldName = getNewFieldName(s"$fieldNamePrefix${field.name}")
            fields += expr(s"$path`${field.name}`").as(newFieldName)
            if (path.contains('['))
              stringFields += s"""expr("$path`${field.name}` AS `$newFieldName`")"""
            else
              stringFields += s"""col("$path`${field.name}`").as("$newFieldName")"""
        }
      })
    }

    flattenGroup("", "", df.schema)
    logger.info(stringFields.mkString("Flattening code: \n.select(\n", ",\n", "\n)"))
    df.select(fields: _*)
  }


  /**
    * Given an instance of DataFrame returns a dataframe where all primitive fields are converted to String
    *
    * @param df A dataframe
    * @return A new dataframe with all primitive fields as Strings.
    */
  def convertDataframeFieldsToStrings(df: DataFrame): DataFrame = {
    val fields = new mutable.ListBuffer[Column]()

    def convertArrayToStrings(path: String, structField: StructField, arrayType: ArrayType): Unit = {
      arrayType.elementType match {
        case st: StructType =>
          // ToDo convert array's inner struct fields to Strings.
          // Possibly Spark 2.4 array transform API could be used for that.
          fields += expr(s"$path`${structField.name}`")
        case fld =>
          fields += expr(s"$path`${structField.name}`").cast(ArrayType(StringType))
      }
    }

    def convertToStrings(path: String, structField: StructType): Unit = {
      structField.foreach(field => {
        field.dataType match {
          case st: StructType =>
            convertToStrings(s"$path`${field.name}`.", st)
          case arr: ArrayType =>
            convertArrayToStrings(path, field, arr)
          case fld =>
            fields += expr(s"$path`${field.name}`").cast(StringType)
        }
      })
    }

    convertToStrings("", df.schema)
    df.select(fields: _*)
  }


  def convertDataFrameToPrettyJSON(df: DataFrame, takeN: Int = 0): String = {
    val collected = if (takeN <= 0) {
      df.toJSON.collect().mkString("\n")
    } else {
      df.toJSON.take(takeN).mkString("\n")
    }

    val json = "[" + "}\n".r.replaceAllIn(collected, "},\n") + "]"

    prettyJSON(json)
  }

  def prettyJSON(jsonIn: String): String = {
    val mapper = new ObjectMapper()

    val jsonUnindented = mapper.readValue(jsonIn, classOf[Any])
    val indented = mapper.writerWithDefaultPrettyPrinter.writeValueAsString(jsonUnindented)
    indented.replace("\r\n", "\n")
  }

  /**
    * Get a Spark field from a text path and a given schema
    * (originally implemented here: https://github.com/AbsaOSS/enceladus/blob/665b34fa1c04fe255729e4b6706cf9ea33227b3e/utils/src/main/scala/za/co/absa/enceladus/utils/schema/SchemaUtils.scala#L45)
    *
    * @param path   The dot-separated path to the field
    * @param schema The schema which should contain the specified path
    * @return Some(the requested field) or None if the field does not exist
    */
  def getField(path: String, schema: StructType): Option[StructField] = {
    @tailrec
    def goThroughArrayDataType(dataType: DataType): DataType = {
      dataType match {
        case ArrayType(dt, _) => goThroughArrayDataType(dt)
        case result => result
      }
    }

    @tailrec
    def examineStructField(names: List[String], structField: StructField): Option[StructField] = {
      if (names.isEmpty) {
        Option(structField)
      } else {
        structField.dataType match {
          case struct: StructType => examineStructField(names.tail, struct(names.head))
          case ArrayType(el: DataType, _) =>
            goThroughArrayDataType(el) match {
              case struct: StructType => examineStructField(names.tail, struct(names.head))
              case _ => None
            }
          case _ => None
        }
      }
    }

    val pathTokens = splitFieldPath(path)
    Try {
      examineStructField(pathTokens.tail, schema(pathTokens.head))
    }.getOrElse(None)
  }

  private def splitFieldPath(path: String): List[String] = {
    var state = 0

    var currentField = new StringBuilder()
    val fields = new ListBuffer[String]()

    var i = 0
    while (i < path.length) {
      val c = path(i)

      state match {
        case 0 =>
          // The character might be part of the path
          if (c == '.') {
            fields.append(currentField.toString())
            currentField = new StringBuilder()
          } else if (c == '`') {
            state = 1
          } else if (c == '[') {
            state = 2
          } else {
            currentField.append(c)
          }
        case 1 =>
          // The character is part of the backquoted field name
          if (c == '`') {
            state = 0
          } else {
            currentField.append(c)
          }
        case 2 =>
          // The character is an index (that should be ignored)
          if (c == ']') {
            state = 0
          }
      }
      i += 1
    }
    if (currentField.nonEmpty) {
      fields.append(currentField.toString())
    }
    fields.toList
  }


}
