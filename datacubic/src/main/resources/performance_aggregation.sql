SELECT
	_timestamp,
	subtype,
	app_version,
	system,
	system_version,
	network_type,
	province,
	isp,
	sch,
	request_url,
	sum(total_num) as total_num,
	sum(succeed_num) as succeed_num,
	sum(neterr_num) as neterr_num,
	sum(localerr_num) as localerr_num,
	sum(during_time) as during_time,
	sum(during_time_1s) as during_time_1s,
	sum(during_time_2s) as during_time_2s,
	sum(during_time_3s) as during_time_3s,
	sum(during_time_4s) as during_time_4s,
	sum(during_time_5s) as during_time_5s,
	sum(during_time_long) as during_time_long,
	sum(net_time) as net_time,
	sum(net_time_1s) as net_time_1s,
	sum(net_time_2s) as net_time_2s,
	sum(net_time_3s) as net_time_3s,
	sum(net_time_4s) as net_time_4s,
	sum(net_time_5s) as net_time_5s,
	sum(net_time_long) as net_time_long,
	sum(local_time) as local_time,
	sum(parse_time) as parse_time,
	sum(parse_time_1s) as parse_time_1s,
	sum(parse_time_2s) as parse_time_2s,
	sum(parse_time_3s) as parse_time_3s,
	sum(parse_time_4s) as parse_time_4s,
	sum(parse_time_5s) as parse_time_5s,
	sum(parse_time_long) as parse_time_long,
	sum(lw) as lw,
	sum(dl) as dl,
	sum(sc) as sc,
	sum(ssc) as ssc,
	sum(sr) as sr,
	sum(ws) as ws,
	sum(rh) as rh,
	sum(rb) as rb,
	sum(ne) as ne
FROM
	(
		SELECT
			subtype,
			_timestamp,
			CASE
				WHEN app_version IS NULL THEN 'null'
				ELSE app_version
			END AS app_version,
			CASE
            	WHEN system IS NULL THEN 'null'
            	ELSE system
            END AS system,
			CASE
				WHEN system_version IS NULL THEN 'null'
				ELSE system_version
			END AS system_version,
			CASE
				WHEN network_type IS NULL THEN 'null'
				ELSE network_type
			END AS network_type,
			province,
			isp,
			CASE
				WHEN sch IS NULL THEN 'null'
				ELSE sch
			END AS sch,
			CASE
				WHEN request_url IS NULL THEN 'null'
				ELSE request_url
			END AS request_url,
			total_num,
			succeed_num,
			neterr_num,
			localerr_num,
			during_time,
			CASE
		 	    WHEN during_time <= 1000 THEN 1
		 	    ELSE 0
		 	END AS during_time_1s,
		 	CASE
		 	    WHEN during_time > 1000 AND during_time <= 2000 THEN 1
		 	    ELSE 0
		 	END AS during_time_2s,
		 	CASE
		 	    WHEN during_time > 2000 AND during_time <= 3000 THEN 1
		 	    ELSE 0
		 	END AS during_time_3s,
		 	CASE
		 	    WHEN during_time > 3000 AND during_time <= 4000 THEN 1
		 	    ELSE 0
		 	END AS during_time_4s,
		 	CASE
		 	    WHEN during_time > 4000 AND during_time <= 5000 THEN 1
		 	    ELSE 0
		 	END AS during_time_5s,
		 	CASE
		 	    WHEN during_time > 5000 THEN 1
		 	    ELSE 0
		 	END AS during_time_long,
		 	net_time,
			CASE
			    WHEN net_time <= 1000 THEN 1
			    ELSE 0
			END AS net_time_1s,
			CASE
			    WHEN net_time > 1000 AND net_time <= 2000 THEN 1
			    ELSE 0
			END AS net_time_2s,
			CASE
			    WHEN net_time > 2000 AND net_time <= 3000 THEN 1
			    ELSE 0
			END AS net_time_3s,
			CASE
			    WHEN net_time > 3000 AND net_time <= 4000 THEN 1
			    ELSE 0
			END AS net_time_4s,
			CASE
			    WHEN net_time > 4000 AND net_time <= 5000 THEN 1
			    ELSE 0
			END AS net_time_5s,
			CASE
			    WHEN net_time > 5000 THEN 1
			    ELSE 0
			END AS net_time_long,
			local_time,
			parse_time,
			CASE
			    WHEN parse_time <= 1000 THEN 1
			    ELSE 0
			END AS parse_time_1s,
			CASE
			    WHEN parse_time > 1000 AND parse_time <= 2000 THEN 1
			    ELSE 0
			END AS parse_time_2s,
			CASE
			    WHEN parse_time > 2000 AND parse_time <= 3000 THEN 1
			    ELSE 0
			END AS parse_time_3s,
			CASE
			    WHEN parse_time > 3000 AND parse_time <= 4000 THEN 1
			    ELSE 0
			END AS parse_time_4s,
			CASE
			    WHEN parse_time > 4000 AND parse_time <= 5000 THEN 1
			    ELSE 0
			END AS parse_time_5s,
			CASE
			    WHEN parse_time > 5000 THEN 1
			    ELSE 0
			END AS parse_time_long,
			lw,
			dl,
			sc,
			ssc,
			sr,
			ws,
			rh,
			rb,
			ne
		FROM
			(
				SELECT
				    subtype,
				    CASE
				        WHEN time IS NOT NULL THEN time_to_utc_with_interval(CAST(time as BIGINT), 'day')
				        WHEN __date IS NOT NULL THEN time_to_utc_with_interval(CAST((__date * 1000) as BIGINT), 'day')
				        ELSE NULL
				    END AS _timestamp,
				    parseUAInfo(ua)['app_version'] AS app_version,
				    parseUAInfo(ua)['system'] AS system,
				    parseUAInfo(ua)['system_version'] AS system_version,
				    network_type,
				    ipToLocation(ip)['province'] AS province,
				    ipToLocation(ip)['isp'] AS isp,
				    sch,
				    request_url,
				    1 AS total_num,
				    CASE
				        WHEN result_code IN ('0', '-8108', '-4501', '-4098', '-4010', '-1019', '-1018', '-1011', '-1010', '-1008', '-1007', '-1005', '-9', '-105', '-100', '1', '5', '1014', '9109', '20003', '20012', '20015', '20016', '20017', '20018', '20019', '20020', '20021', '20031', '20034', '20046', '20101', '20201', '20130', '20134', '20135', '20148', '20156', '20206', '20208', '20210', '20603', '107002', '50112071', '1001030042', '1078030002') THEN 1
				        ELSE 0
				    END as succeed_num,
				    CASE
				        WHEN result_code IN ('-1202', '-1020', '-1017', '-1009', '-1006', '-1004', '-1003', '-1001', '-200', '-4', '-1', '2', '6', '22', '54', '205', '302', '303', '307', '400', '403', '404', '405', '500', '502', '503', '504', '705', '706', '1101', '7002', '7003', '7004', '105001', '3020003') THEN 1
				        ELSE 0
				    END AS neterr_num,
				    CASE
				        WHEN result_code IN ('7', '7001', '3840', '7005', '8998', '9102', '20120', '20205', '201603', 'e000', '3', '4', '1040002', '3020005', '3020006', '10001', '10009', '10011') THEN 1
				        ELSE 0
				    END AS localerr_num,
				    CASE
				        WHEN during_time IS NOT NULL THEN CAST(during_time AS BIGINT)
				        ELSE 0
				    END AS during_time,
				    CASE
				        WHEN net_time IS NOT NULL THEN CAST(net_time AS BIGINT)
				        ELSE 0
				    END AS net_time,
				    CAST((during_time - net_time) AS BIGINT) AS local_time,
				    CASE
				        WHEN parseTime IS NOT NULL THEN CAST(parseTime AS BIGINT)
				        ELSE 0
				    END AS parse_time,
				    CASE
				        WHEN lw IS NOT NULL THEN CAST(lw AS BIGINT)
				        ELSE 0
				    END AS lw,
				    CASE
				        WHEN dl IS NOT NULL THEN CAST(dl AS BIGINT)
				        ELSE 0
				    END AS dl,
				    CASE
				        WHEN sc IS NOT NULL THEN CAST(sc AS BIGINT)
				        ELSE 0
				    END AS sc,
				    CASE
				        WHEN ssc IS NOT NULL THEN CAST(ssc AS BIGINT)
				        ELSE 0
				    END AS ssc,
				    CASE
				        WHEN sr IS NOT NULL THEN CAST(sr AS BIGINT)
				        ELSE 0
				    END AS sr,
				    CASE
				        WHEN ws IS NOT NULL THEN CAST(ws AS BIGINT)
				        ELSE 0
				    END AS ws,
				    CASE
				        WHEN rh IS NOT NULL THEN CAST(rh AS BIGINT)
				        ELSE 0
				    END AS rh,
				    CASE
				        WHEN rb IS NOT NULL THEN CAST(rb AS BIGINT)
				        ELSE 0
				    END AS rb,
				    CASE
				        WHEN ne = 'cn0' THEN 0
				        ELSE 1
				    END AS ne
				FROM
				    source_table
			) translate_table
		WHERE
		    subtype = 'refresh_feed'
		    AND _timestamp IS NOT NULL
		    AND during_time >= 0 AND during_time < 10000
		    AND net_time >= 0 AND net_time < 10000
		    AND local_time >= 0 AND local_time < 10000
		    AND parse_time >= 0 AND parse_time < 10000
		    AND lw >= 0 AND lw < 10000
		    AND dl >= 0 AND dl < 10000
		    AND sc >= 0 AND sc < 10000
		    AND ssc >= 0 AND ssc < 10000
		    AND sr >= 0 AND sr < 10000
		    AND ws >= 0 AND ws < 10000
		    AND rh >= 0 AND rh < 10000
		    AND rb >= 0 AND rb < 10000
		) filter_table
GROUP BY
	_timestamp,
	subtype,
	app_version,
	system,
	system_version,
	network_type,
	province,
	isp,
	sch,
	request_url