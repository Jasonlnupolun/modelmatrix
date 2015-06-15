package com.collective.modelmatrix.cli

import org.scalatest.FlatSpec

class SourceSpec extends FlatSpec {

  "Source" should "parse hive source" in {
    val source = "hive://mm.input"
    val parsed = Source(source)
    assert(parsed == HiveSource("mm.input"))
  }

  it should "parse parquet source" in {
    val source = "parquet://file://temp/data/output.parquet"
    val parsed = Source(source)
    assert(parsed == ParquetSource("file://temp/data/output.parquet"))
  }

}
