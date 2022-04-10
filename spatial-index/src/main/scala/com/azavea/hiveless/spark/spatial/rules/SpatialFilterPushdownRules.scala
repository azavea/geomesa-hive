/*
 * Copyright 2022 Azavea
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

package com.azavea.hiveless.spark.spatial.rules

import com.azavea.hiveless.spark.rules.syntax._
import com.azavea.hiveless.spatial._
import com.azavea.hiveless.spatial.index._
import com.azavea.hiveless.spatial.index.ST_Intersects
import com.azavea.hiveless.serializers.UnaryDeserializer.Errors.ProductDeserializationError
import com.azavea.hiveless.serializers.syntax._
import geotrellis.vector._
import cats.syntax.option._
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.spark.sql.hive.HivelessInternals.HiveGenericUDF
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.plans.logical.{Filter, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.log4s.getLogger

import scala.util.{Failure, Success, Try}

object SpatialFilterPushdownRules extends Rule[LogicalPlan] {
  @transient private[this] lazy val logger = getLogger

  def apply(plan: LogicalPlan): LogicalPlan =
    plan.transformDown {
      case f @ Filter(condition: HiveGenericUDF, plan) if condition.of[ST_Intersects] =>
        try {
          val Seq(extentExpr, geometryExpr) = condition.children

          // ST_Intersects is polymorphic by the first argument
          // Optimization is done only when the first argument is Extent
          if (!extentExpr.dataType.conformsToSchema(extentEncoder.schema))
            throw new UnsupportedOperationException(
              s"${classOf[ST_Intersects]} push-down optimization works on the Extent column data type only."
            )

          // transform expression
          val expr = Try(geometryExpr.eval(null)) match {
            // Literals push-down support only
            case Success(g) =>
              // ST_Intersects is polymorphic by the second argument
              // Extract Extent literal from the right
              // The second argument can be Geometry or Extent
              val extent = Try(g.convert[Geometry].extent)
                .orElse(Try(g.convert[Extent]))
                .getOrElse(throw ProductDeserializationError[ST_Intersects.Arg](classOf[ST_Intersects], "second"))

              // transform expression
              AndList(
                List(
                  IsNotNull(extentExpr),
                  GreaterThanOrEqual(GetStructField(extentExpr, 0, "xmin".some), Literal(extent.xmin)),
                  GreaterThanOrEqual(GetStructField(extentExpr, 1, "ymin".some), Literal(extent.ymin)),
                  LessThanOrEqual(GetStructField(extentExpr, 2, "xmax".some), Literal(extent.xmax)),
                  LessThanOrEqual(GetStructField(extentExpr, 3, "ymax".some), Literal(extent.ymax))
                )
              )
            // Expression
            case Failure(_) =>
              // In case on the right we have an Expression, no further optimizations needed and
              // such predicates won't be pushed down.
              //
              // In case Geometry is on the right, we can't extract Envelope coordinates, to perform it we need to define
              // User Defined Expression and that won't be pushed down.
              //
              // However, it is possible to extract coordinates out of Extent.
              // In this case the GetStructField can be used to extract values and transform the request,
              // though such predicates are not pushed down as well.
              //
              // The rough implementation of the idea above (The transformed plan for Extent, which is not pushed down):
              /*if (geometryExpr.dataType.conformsToSchema(extentEncoder.schema)) {
                AndList(
                  List(
                    IsNotNull(extentExpr),
                    GreaterThanOrEqual(GetStructField(extentExpr, 0, "xmin".some), GetStructField(geometryExpr, 0, "xmin".some)),
                    GreaterThanOrEqual(GetStructField(extentExpr, 1, "ymin".some), GetStructField(geometryExpr, 1, "ymin".some)),
                    LessThanOrEqual(GetStructField(extentExpr, 2, "xmax".some), GetStructField(geometryExpr, 2, "xmax".some)),
                    LessThanOrEqual(GetStructField(extentExpr, 3, "ymax".some), GetStructField(geometryExpr, 3, "ymax".some))
                  )
                )
              } else {
                throw new UnsupportedOperationException(
                  "Geometry Envelope values extraction is not supported by the internal Geometry representation.".stripMargin
                )
              }*/

              throw new UnsupportedOperationException(
                s"${classOf[ST_Intersects]} push-down optimization works with Geometry and Extent Literals only."
              )
          }

          Filter(expr, plan)
        } catch {
          // fallback to the unoptimized node if optimization failed
          case e: Throwable =>
            logger.warn(
              s"""
                 |${this.getClass.getName} ${classOf[ST_Intersects]} optimization failed.
                 |StackTrace: ${ExceptionUtils.getStackTrace(e)}
                 |""".stripMargin
            )
            f
        }
    }

  def registerOptimizations(sqlContext: SQLContext): Unit =
    Seq(SpatialFilterPushdownRules).foreach { r =>
      if (!sqlContext.experimental.extraOptimizations.contains(r))
        sqlContext.experimental.extraOptimizations ++= Seq(r)
    }
}
