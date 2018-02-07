import org.apache.spark
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

case class Person(name: String, age: Long)

object SparkSQLSimpleExample {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession
      .builder()
      .appName("Spark SQL basic example")
      .config("spark.some.config.option", "some-value")
      .getOrCreate()

    // For implicit conversions from RDDs to DataFrames
    import spark.implicits._

    // Create an RDD of Person objects from a text file, convert it to a Dataframe
    val peopleDF = spark.sparkContext
      .textFile("/Users/petec/spark-2.2.1-bin-hadoop2.7/examples/src/main/resources/people.txt")
      .map(_.split(","))
      .map(attributes => Person(attributes(0), attributes(1).trim.toInt))
      .toDF()
    // Register the DataFrame as a temporary view
    peopleDF.createOrReplaceTempView("people")

    // SQL statements can be run by using the sql methods provided by Spark
    val teenagersDF = spark.sql("SELECT name, age FROM people WHERE age BETWEEN 13 AND 19")

    // The columns of a row in the result can be accessed by field index
    teenagersDF.map(teenager => "Name: " + teenager(0)).show()
    // +------------+
    // |       value|
    // +------------+
    // |Name: Justin|
    // +------------+

    // or by field name
    teenagersDF.map(teenager => "Name: " + teenager.getAs[String]("name")).show()
    // +------------+
    // |       value|
    // +------------+
    // |Name: Justin|
    // +------------+

    // No pre-defined encoders for Dataset[Map[K,V]], define explicitly
    implicit val mapEncoder = org.apache.spark.sql.Encoders.kryo[Map[String, Any]]
    // Primitive types and case classes can be also defined as
    // implicit val stringIntMapEncoder: Encoder[Map[String, Any]] = ExpressionEncoder()

    // row.getValuesMap[T] retrieves multiple columns at once into a Map[String, T]
    teenagersDF.map(teenager => teenager.getValuesMap[Any](List("name", "age"))).collect().foreach(println)
    // Array(Map("name" -> "Justin", "age" -> 19))

  }

}


object SparkSQLSimpleExample1 {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession
      .builder()
      .appName("Spark SQL basic example")
      .config("spark.some.config.option", "some-value")
      .getOrCreate()

    // For implicit conversions from RDDs to DataFrames
    import spark.implicits._
    import org.apache.spark.sql.types._

    // Create an RDD
    val peopleRDD = spark.sparkContext.textFile("/Users/petec/spark-2.2.1-bin-hadoop2.7/examples/src/main/resources/people.txt")

    // The schema is encoded in a string
    val schemaString = "name age"

    // Generate the schema based on the string of schema
    val fields = schemaString.split(" ")
      .map(fieldName => StructField(fieldName, StringType, nullable = true))
    val schema = StructType(fields)

    // Convert records of the RDD (people) to Rows
    val rowRDD: RDD[Row] = peopleRDD
      .map(_.split(","))
      .map(attributes => Row(attributes(0), attributes(1).trim))

    // Apply the schema to the RDD
    val peopleDF = spark.createDataFrame(rowRDD, schema)

    // Creates a temporary view using the DataFrame
    peopleDF.createOrReplaceTempView("people")

    // SQL can be run over a temporary view created using DataFrames
    val results = spark.sql("SELECT name, age FROM people")

    // The results of SQL queries are DataFrames and support all the normal RDD operations
    // The columns of a row in the result can be accessed by field index or by field name
    results.map(attributes => "Name: " + attributes(0) + ", Age: " + attributes(1)).show(20, truncate=false)

  }

}


object UserDefinedUntypedAggregation {

  import org.apache.spark.sql.expressions.MutableAggregationBuffer
  import org.apache.spark.sql.expressions.UserDefinedAggregateFunction
  import org.apache.spark.sql.types._
  import org.apache.spark.sql.Row
  import org.apache.spark.sql.SparkSession

  def main(args: Array[String]): Unit = {

    val spark = SparkSession
      .builder()
      .appName("UntypedUserDefinedAggregateFunctionsExample")
      .config("spark.some.config.option", "some-value")
      .getOrCreate()

    // For implicit conversions from RDDs to DataFrames
    import spark.implicits._
    import org.apache.spark.sql.types._

    object MyAverage extends UserDefinedAggregateFunction {
      // Data types of input arguments of this aggregate function
      def inputSchema: StructType = StructType(StructField("inputColumn", LongType) :: Nil)

      // Data types of values in the aggregation buffer
      def bufferSchema: StructType = {
        StructType(StructField("sum", LongType) :: StructField("count", LongType) :: Nil)
      }

      // The data type of the returned value
      def dataType: DataType = DoubleType

      // Whether this function always returns the same output on the identical input
      def deterministic: Boolean = true

      // Initializes the given aggregation buffer. The buffer itself is a `Row` that in addition to
      // standard methods like retrieving a value at an index (e.g., get(), getBoolean()), provides
      // the opportunity to update its values. Note that arrays and maps inside the buffer are still
      // immutable.
      def initialize(buffer: MutableAggregationBuffer): Unit = {
        buffer(0) = 0L
        buffer(1) = 0L
      }

      // Updates the given aggregation buffer `buffer` with new input data from `input`
      def update(buffer: MutableAggregationBuffer, input: Row): Unit = {
        if (!input.isNullAt(0)) {
          buffer(0) = buffer.getLong(0) + input.getLong(0)
          buffer(1) = buffer.getLong(1) + 1
        }
      }

      // Merges two aggregation buffers and stores the updated buffer values back to `buffer1`
      def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {
        buffer1(0) = buffer1.getLong(0) + buffer2.getLong(0)
        buffer1(1) = buffer1.getLong(1) + buffer2.getLong(1)
      }

      // Calculates the final result
      def evaluate(buffer: Row): Double = buffer.getLong(0).toDouble / buffer.getLong(1)
    }

    // Register the function to access it
    spark.udf.register("myAverage", MyAverage)

    val df = spark.read.json("/Users/petec/spark-2.2.1-bin-hadoop2.7/examples/src/main/resources/employees.json")
    df.createOrReplaceTempView("employees")
    df.show()

    val result = spark.sql("SELECT myAverage(salary) as average_salary FROM employees")
    result.show()

    spark.stop()
  }

}


import org.apache.spark.sql.{Encoder, Encoders, SparkSession}
import org.apache.spark.sql.expressions.Aggregator

object UserDefinedTypedAggregation {

  case class Employee(name: String, salary: Long)
  case class Average(var sum: Long, var count: Long)

  object MyAverage extends Aggregator[Employee, Average, Double] {
    // A zero value for this aggregation. Should satisfy the property that any b + zero = b
    def zero: Average = Average(0L, 0L)
    // Combine two values to produce a new value. For performance, the function may modify `buffer`
    // and return it instead of constructing a new object
    def reduce(buffer: Average, employee: Employee): Average = {
      buffer.sum += employee.salary
      buffer.count += 1
      buffer
    }
    // Merge two intermediate values
    def merge(b1: Average, b2: Average): Average = {
      b1.sum += b2.sum
      b1.count += b2.count
      b1
    }
    // Transform the output of the reduction
    def finish(reduction: Average): Double = reduction.sum.toDouble / reduction.count
    // Specifies the Encoder for the intermediate value type
    def bufferEncoder: Encoder[Average] = Encoders.product
    // Specifies the Encoder for the final output value type
    def outputEncoder: Encoder[Double] = Encoders.scalaDouble
  }


  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder()
      .appName("Spark SQL user-defined Datasets aggregation example")
      .getOrCreate()

    import spark.implicits._

    // $example on:typed_custom_aggregation$
    val ds = spark.read.json("/Users/petec/spark-2.2.1-bin-hadoop2.7/examples/src/main/resources/employees.json").as[Employee]
    ds.show()

    // Convert the function to a `TypedColumn` and give it a name
    val averageSalary = MyAverage.toColumn.name("average_salary")
    val result = ds.select(averageSalary)
    result.show()

    spark.stop()
  }

}


object ParquetExample {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder()
      .appName("Spark Parquet import/export example")
      .getOrCreate()

    import spark.implicits._

    val peopleDF = spark.read.json("/Users/petec/spark-2.2.1-bin-hadoop2.7/examples/src/main/resources/people.json")

    // DataFrames can be saved as Parquet files, maintaining the schema information
    peopleDF.write.parquet("people.parquet")

    // Read in the parquet file created above
    // Parquet files are self-describing so the schema is preserved
    // The result of loading a Parquet file is also a DataFrame
    val parquetFileDF = spark.read.parquet("people.parquet")

    // Parquet files can also be used to create a temporary view and then used in SQL statements
    parquetFileDF.createOrReplaceTempView("parquetFile")
    val namesDF = spark.sql("SELECT name FROM parquetFile WHERE age BETWEEN 13 AND 19")
    namesDF.map(attributes => "Name: " + attributes(0)).show()
    // +------------+
    // |       value|
    // +------------+
    // |Name: Justin|
    // +------------+
  }
}


object SchemaMergingExample {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder()
      .appName("Spark Parquet schema merging example")
      .getOrCreate()

    // This is used to implicitly convert an RDD to a DataFrame.
    import spark.implicits._

    // Create a simple DataFrame, store into a partition directory
    val squaresDF = spark.sparkContext.makeRDD(1 to 5).map(i => (i, i * i)).toDF("value", "square")
    squaresDF.write.parquet("data/test_table/key=1")

    // Create another DataFrame in a new partition directory,
    // adding a new column and dropping an existing column
    val cubesDF = spark.sparkContext.makeRDD(6 to 10).map(i => (i, i * i * i)).toDF("value", "cube")
    cubesDF.write.parquet("data/test_table/key=2")

    // Read the partitioned table
    val mergedDF = spark.read.option("mergeSchema", "true").parquet("data/test_table")
    mergedDF.printSchema()

    // The final schema consists of all 3 columns in the Parquet files together
    // with the partitioning column appeared in the partition directory paths
    // root
    //  |-- value: int (nullable = true)
    //  |-- square: int (nullable = true)
    //  |-- cube: int (nullable = true)
    //  |-- key: int (nullable = true)
  }
}