import org.apache.spark.sql.SparkSession


object SparkSQLSimpleExample {

  def main(args: Array[String]) = {

    val spark = SparkSession
      .builder()
      .appName("Spark SQL basic example")
      .config("spark.some.config.option", "some-value")
      .getOrCreate()

    // For implicit conversions like converting RDDs to DataFrames
    import spark.implicits._

    val df = spark.read.json("/Users/petec/spark-2.2.1-bin-hadoop2.7/examples/src/main/resources/people.json")

    // Displays the content of the DataFrame to stdout
    df.show()

  }

}
