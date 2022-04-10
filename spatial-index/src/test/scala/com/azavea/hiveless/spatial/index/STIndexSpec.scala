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

package com.azavea.hiveless.spatial.index

import com.azavea.hiveless.{SpatialIndexHiveTestEnvironment, SpatialIndexTestTables}
import com.azavea.hiveless.spark.encoders.syntax._
import geotrellis.vector.Extent
import org.apache.spark.sql.catalyst.plans.logical.Filter
import org.scalatest.funspec.AnyFunSpec

class STIndexSpec extends AnyFunSpec with SpatialIndexHiveTestEnvironment with SpatialIndexTestTables {

  describe("ST Index functions spec") {
    it("ST_ExtentFromGeom") {
      val df = ssc.sql(
        """
          |SELECT ST_ExtentFromGeom(ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[-75.5859375,40.32517767999294],[-75.5859375,43.197167282501276],[-72.41015625,43.197167282501276],[-72.41015625,40.32517767999294],[-75.5859375,40.32517767999294]]]}'))
          |""".stripMargin
      )

      df.head().getStruct(0).as[Extent] shouldBe Extent(-75.5859375, 40.3251777, -72.4101562, 43.1971673)
    }
    it("ST_Intersects should filter a CSV file") {
      val df = ssc.sql(
        """
          |SELECT * FROM polygons_csv_view WHERE ST_Intersects(bbox, ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[-75.5859375,40.32517767999294],[-75.5859375,43.197167282501276],[-72.41015625,43.197167282501276],[-72.41015625,40.32517767999294],[-75.5859375,40.32517767999294]]]}'))
          |""".stripMargin
      )

      df.count() shouldBe 5
    }

    it("ST_Intersects should filter a Parquet file") {
      val df = ssc.sql(
        """
          |SELECT * FROM polygons_parquet WHERE ST_Intersects(bbox, ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[-75.5859375,40.32517767999294],[-75.5859375,43.197167282501276],[-72.41015625,43.197167282501276],[-72.41015625,40.32517767999294],[-75.5859375,40.32517767999294]]]}'))
          |""".stripMargin
      )

      df.count() shouldBe 5
    }

    it("ST_Intersects plan should be optimized") {
      val df = ssc.sql(
        """
          |SELECT * FROM polygons_parquet WHERE ST_Intersects(bbox, ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[-75.5859375,40.32517767999294],[-75.5859375,43.197167282501276],[-72.41015625,43.197167282501276],[-72.41015625,40.32517767999294],[-75.5859375,40.32517767999294]]]}'))
          |""".stripMargin
      )

      val dfe = ssc.sql(
        """
          |SELECT * FROM polygons_parquet
          |WHERE bbox.xmin >= -75.5859375
          |AND bbox.ymin >= 40.3251777
          |AND bbox.xmax <= -72.4101562
          |AND bbox.ymax <= 43.1971673
          |""".stripMargin
      )

      df.count() shouldBe dfe.count()

      // compare optimized plans filters
      val dfc  = df.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }
      val dfec = dfe.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }

      dfc shouldBe dfec
    }

    it("ST_Intersects by Extent plan should be optimized") {
      val df = ssc.sql(
        """
          |SELECT * FROM polygons_parquet WHERE ST_Intersects(bbox, ST_MakeExtent(-75.5859375, 40.3251777, -72.4101562, 43.1971673))
          |""".stripMargin
      )

      val dfe = ssc.sql(
        """
          |SELECT * FROM polygons_parquet
          |WHERE bbox.xmin >= -75.5859375
          |AND bbox.ymin >= 40.3251777
          |AND bbox.xmax <= -72.4101562
          |AND bbox.ymax <= 43.1971673
          |""".stripMargin
      )

      df.count() shouldBe dfe.count()

      // compare optimized plans filters
      val dfc  = df.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }
      val dfec = dfe.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }

      dfc shouldBe dfec
    }

    it("ST_Intersects optimization failure (Extent, Extent)") {
      val df = ssc.sql(
        """
          |SELECT * FROM polygons_parquet WHERE ST_Intersects(bbox, bbox)
          |""".stripMargin
      )

      val dfe = ssc.sql(
        """
          |SELECT * FROM polygons_parquet
          |WHERE bbox.xmin >= -75.5859375
          |AND bbox.ymin >= 40.3251777
          |AND bbox.xmax <= -72.4101562
          |AND bbox.ymax <= 43.1971673
          |""".stripMargin
      )

      df.count() shouldBe dfe.count()

      // compare optimized plans filters
      val dfc  = df.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }
      val dfec = dfe.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }

      dfc shouldNot be(dfec)
    }

    it("ST_Intersects optimization failure (Extent, Geometry)") {
      val df = ssc.sql(
        """
          |SELECT * FROM polygons_parquet WHERE ST_Intersects(bbox, geom)
          |""".stripMargin
      )

      val dfe = ssc.sql(
        """
          |SELECT * FROM polygons_parquet
          |WHERE bbox.xmin >= -75.5859375
          |AND bbox.ymin >= 40.3251777
          |AND bbox.xmax <= -72.4101562
          |AND bbox.ymax <= 43.1971673
          |""".stripMargin
      )

      df.count() shouldBe dfe.count()

      // compare optimized plans filters
      val dfc  = df.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }
      val dfec = dfe.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }

      dfc shouldNot be(dfec)
    }

    it("ST_Intersects optimization failure (Geometry, Geometry)") {
      val df = ssc.sql(
        """
          |SELECT * FROM polygons_parquet WHERE ST_Intersects(geom, geom)
          |""".stripMargin
      )

      val dfe = ssc.sql(
        """
          |SELECT * FROM polygons_parquet
          |WHERE bbox.xmin >= -75.5859375
          |AND bbox.ymin >= 40.3251777
          |AND bbox.xmax <= -72.4101562
          |AND bbox.ymax <= 43.1971673
          |""".stripMargin
      )

      df.count() shouldBe dfe.count()

      // compare optimized plans filters
      val dfc  = df.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }
      val dfec = dfe.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }

      dfc shouldNot be(dfec)
    }

    it("ST_Intersects optimization failure (Geometry, Extent)") {
      val df = ssc.sql(
        """
          |SELECT * FROM polygons_parquet WHERE ST_Intersects(geom, bbox)
          |""".stripMargin
      )

      val dfe = ssc.sql(
        """
          |SELECT * FROM polygons_parquet
          |WHERE bbox.xmin >= -75.5859375
          |AND bbox.ymin >= 40.3251777
          |AND bbox.xmax <= -72.4101562
          |AND bbox.ymax <= 43.1971673
          |""".stripMargin
      )

      df.count() shouldBe dfe.count()

      // compare optimized plans filters
      val dfc  = df.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }
      val dfec = dfe.queryExecution.optimizedPlan.collect { case Filter(condition, _) => condition }

      dfc shouldNot be(dfec)
    }
  }
}
