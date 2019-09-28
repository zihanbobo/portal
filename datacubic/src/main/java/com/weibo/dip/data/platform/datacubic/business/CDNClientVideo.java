package com.weibo.dip.data.platform.datacubic.business;


import com.weibo.dip.data.platform.commons.util.GsonUtil;
import com.weibo.dip.data.platform.datacubic.streaming.udf.IpToLocation;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.CharEncoding;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CDNClientVideo {
    private static final Logger LOGGER = LoggerFactory.getLogger(CDNClientVideo.class);

    private static String getPath(String[] args) {
        return ArrayUtils.isNotEmpty(args) ? "/user/hdfs/rawlog/app_picserversweibof6vwt_wapvideodownload/" + args[0] :
                "/user/hdfs/rawlog/app_picserversweibof6vwt_wapvideodownload/2018_03_19/*";
    }

    private static String getSQL() throws Exception {
        StringBuilder sql = new StringBuilder();

        BufferedReader reader = null;

        try {
            reader = new BufferedReader(
                    new InputStreamReader(
                            Ideo_Download_Base2slow.class.getClassLoader()
                                    .getResourceAsStream("cdn_video.sql"),
                            CharEncoding.UTF_8));

            String line;

            while ((line = reader.readLine()) != null) {
                sql.append(line);
                sql.append("\n");
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }

        return sql.toString();
    }

    public static void main(String[] args) throws Exception {
        SparkConf conf = new SparkConf();

        JavaSparkContext context = new JavaSparkContext(conf);

        SparkSession session = SparkSession.builder().enableHiveSupport().getOrCreate();

        session.udf().register("ipToLocation", new IpToLocation(), DataTypes.createMapType(DataTypes
                .StringType, DataTypes.StringType));

        String[] fieldNames = {"ip",
                "dst_ip",
                "cdn",
                "video_cache_type",
                "video_firstframe_status",
                "video_quit_status",
                "video_firstframe_time",
                "domain",
                "protocal"};

        List<StructField> fields = new ArrayList<>();

        for (String fieldName : fieldNames) {
            fields.add(DataTypes.createStructField(fieldName, DataTypes.StringType, true));
        }

        StructType schema = DataTypes.createStructType(fields);

        JavaRDD<String> lines_1217 = context.textFile(getPath(args));

        JavaRDD<Row> rowRDD = lines_1217.map((String line) -> {
            String[] row = {"","","","","","","","",""};
            try {
                if (line.split("`").length == 2 && line.split("`")[0] != null) {
                    Map<String, Object> lrs = GsonUtil.fromJson(line.split("`")[0], GsonUtil.GsonType.OBJECT_MAP_TYPE);

                    String domain;
                    String protocal;
                    String cdn = lrs.containsKey("video_cdn") ? (String) lrs.get("video_cdn"): "";

                    if (lrs.containsKey("video_url") && lrs.get("video_url") != null) {
                        URL pic_url = new URL((String) lrs.get("video_url"));
                        domain = pic_url.getHost();
                        protocal = pic_url.getProtocol();
                    } else {
                        domain = "";
                        protocal = "";
                    }

                    row = new String[]{
                            lrs.containsKey("ip") ? (String) lrs.get("ip"): "",
                            lrs.containsKey("dst_ip") ? (String) lrs.get("dst_ip"): "",
                            cdn.contains("f=") && cdn.contains(",") ? cdn.substring(cdn.indexOf("f=") + 2, cdn.indexOf(",") ):"",
                            lrs.containsKey("video_cache_type") ? (String) lrs.get("video_cache_type"): "",
                            lrs.containsKey("video_firstframe_status") ? (String) lrs.get("video_firstframe_status"): "",
                            lrs.containsKey("video_quit_status") ? (String) lrs.get("video_quit_status"): "",
                            lrs.containsKey("video_firstframe_time") ? lrs.get("video_firstframe_time").toString() : "0",
                            domain,
                            protocal};
                }
            }catch(Exception e){
                LOGGER.error("----------------Parse Error:" + " " + line +  ExceptionUtils.getFullStackTrace(e));
            }

                String[] values = new String[fieldNames.length];

                System.arraycopy(row, 0, values, 0, fieldNames.length);

                return RowFactory.create((Object[]) values);

            }).filter(Objects::nonNull);

        Dataset<Row> sourceDS = session.createDataFrame(rowRDD, schema);

        sourceDS.createOrReplaceTempView("cdn_video");

        Dataset<Row> resultDS = session.sql(getSQL());

        resultDS.javaRDD().repartition(1).saveAsTextFile("/tmp/cdn_video");

        context.stop();

    }
}
