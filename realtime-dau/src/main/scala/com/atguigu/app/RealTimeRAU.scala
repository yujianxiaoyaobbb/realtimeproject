package com.atguigu.app

import java.text.SimpleDateFormat
import java.util
import java.util.Date

import com.alibaba.fastjson.JSON
import com.atguigu.GmallConstants
import com.atguigu.bean.StartUpLog
import com.atguigu.utils.{MyKafkaUtil, RedisUtil}
import org.apache.spark.SparkConf
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import redis.clients.jedis.Jedis


object RealTimeRAU {
  def main(args: Array[String]): Unit = {
    val sdf = new SimpleDateFormat("yyyy-MM-dd HH")
    val conf: SparkConf = new SparkConf().setAppName("RealTimeRAU").setMaster("local[*]")

    //redis key
    val redisKey = "duplicate"

    //创建ssc
    val ssc = new StreamingContext(conf,Seconds(5))
    //读取kafka数据
    val kafkaDStream: InputDStream[(String, String)] = MyKafkaUtil.getKafkaStream(ssc,Set(GmallConstants.KAFKA_TOPIC_STARTUP))

    //将数据转换成样例类
    val startUpLogDStream: DStream[StartUpLog] = kafkaDStream.map {
      case (_, value) => {
        //将json字符串转换为json
        val jsonLog: StartUpLog = JSON.parseObject(value, classOf[StartUpLog])
        //获取ts
        val ts: Long = jsonLog.ts
        //用日期格式化
        val sdfDate: String = sdf.format(new Date(ts))
        val splits: Array[String] = sdfDate.split(" ")
        jsonLog.logDate = splits(0)
        jsonLog.logHour = splits(1)
        jsonLog
      }
    }
//    startUpLogDStream.print()
    //过滤之前数据中已经登陆过的用户数据
    val filterStartLogDStream: DStream[StartUpLog] = startUpLogDStream.transform(rdd => {
      //创建redis连接
      val client: Jedis = RedisUtil.getJedisClient
      //拿到存放重复mid的集合并放入广播变量中
      val duplicateMembers: util.Set[String] = client.smembers(redisKey)
      val duplicateMembersBC: Broadcast[util.Set[String]] = ssc.sparkContext.broadcast(duplicateMembers)
      //关闭redis连接
      client.close()
      //过滤数据
      rdd.filter(data=> !duplicateMembersBC.value.contains(data.mid))
    })

    //批次内进行过滤
    val filterStartLogByMidDStream: DStream[(String, Iterable[StartUpLog])]
    = filterStartLogDStream.map(data=>(data.mid,data)).groupByKey()
    //只要mid
    val midDStream: DStream[String] = filterStartLogByMidDStream.map(_._1)
    //将新增的mid加入到重复mid中
    midDStream.foreachRDD(rdd=>{
      rdd.foreachPartition(itr=>{
        //创建redis连接
        val client: Jedis = RedisUtil.getJedisClient
        itr.foreach(data=>{
          client.sadd(redisKey,data)
        })
        //关闭redis连接
        client.close()
      })
    })

    //开启任务
    ssc.start()
    ssc.awaitTermination()
  }
}
