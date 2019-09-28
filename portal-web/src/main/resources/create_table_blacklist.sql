CREATE TABLE IF NOT EXISTS `warning_blacklist`(

`id` INT UNSIGNED AUTO_INCREMENT COMMENT 'INDEX ID',

`black_name` VARCHAR(100) UNIQUE NOT NULL COMMENT '黑名单名称',

`update_time` TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',

`business` VARCHAR(100) NOT NULL COMMENT '业务名称',

`dimensions` TEXT NOT NULL COMMENT '维度条件',

`begin_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',

`finish_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '结束时间',

PRIMARY KEY (`id`)

)ENGINE=InnoDB DEFAULT CHARSET=utf8;