-- OfferMate 演示数据（可选）：初始化脚本 schema.sql 之后执行本文件
-- 覆盖场景：2 个招聘方（HR）、1 个求职者、1 个管理员、10 个在招职位
-- 所有演示账号密码统一为 123456（BCrypt 加密后入库）
-- id 从 9001 起（避开库里可能已有的数据），配合 INSERT IGNORE，重复执行不会产生重复数据
USE offermate;

INSERT IGNORE INTO sys_user (id, phone, password, nickname, role, status) VALUES
  (9001, '13811110001', '$2a$10$ImrToUAzwWh7c0cRBJJNYu49DzGnqWMLntxncarxoct63s6A6HTmm', '张伟（招聘方）', 'HR', 1),
  (9002, '13811110002', '$2a$10$ImrToUAzwWh7c0cRBJJNYu49DzGnqWMLntxncarxoct63s6A6HTmm', '李娜（招聘方）', 'HR', 1),
  (9003, '13911110001', '$2a$10$ImrToUAzwWh7c0cRBJJNYu49DzGnqWMLntxncarxoct63s6A6HTmm', '小明（求职者）', 'SEEKER', 1),
  (9004, '13711110001', '$2a$10$ImrToUAzwWh7c0cRBJJNYu49DzGnqWMLntxncarxoct63s6A6HTmm', '平台管理员', 'ADMIN', 1);

INSERT IGNORE INTO job (id, hr_id, title, city, salary_min, salary_max, skills, jd, head_count, status, audit_status) VALUES
  (9001, 9001, '后端开发工程师', '北京', 15, 25, 'Java,SpringBoot,MySQL,Redis',
   '负责招聘业务后端的开发与优化，参与分片上传、异步消息、限流等高并发场景的落地；要求熟悉 Java 与主流中间件。', 3, 1, 1),
  (9002, 9001, 'Java 开发工程师（校招）', '北京', 12, 18, 'Java,Spring,MyBatis',
   '校招岗位，参与业务系统开发；有扎实的 Java 基础、了解 MySQL 与 Redis 即可，期待有个人项目的同学。', 5, 1, 1),
  (9003, 9001, '高级后端工程师', '上海', 25, 40, 'Java,分布式,RocketMQ,MySQL',
   '负责核心链路的重构与稳定性建设，要求有消息队列、分布式锁、链路追踪的实践经验。', 2, 1, 1),
  (9004, 9001, '前端开发工程师', '北京', 14, 22, 'Vue,JavaScript,CSS',
   '负责招聘平台 Web 端开发，参与大文件上传交互、进度与续传体验的优化。', 2, 1, 1),
  (9005, 9002, '数据开发工程师', '杭州', 18, 30, 'SQL,Spark,Hadoop',
   '负责数据仓库建设与离线/实时任务开发，支撑招聘漏斗与转化分析。', 2, 1, 1),
  (9006, 9002, '测试开发工程师', '深圳', 13, 20, 'Java,自动化测试,Linux',
   '负责接口自动化与性能测试，参与压测方案设计与瓶颈定位。', 2, 1, 1),
  (9007, 9002, '算法工程师（NLP）', '北京', 25, 45, 'Python,大模型,NLP',
   '负责简历解析与职位匹配相关的算法落地，有大模型应用经验者优先。', 1, 1, 1),
  (9008, 9002, '运维开发工程师', '上海', 15, 22, 'Linux,Docker,K8s',
   '负责中间件与服务的部署运维，保障 MySQL、Redis、RocketMQ 集群稳定。', 1, 1, 1),
  (9009, 9001, '产品经理（招聘方向）', '深圳', 12, 20, '需求分析,原型设计',
   '负责招聘产品需求梳理与方案设计，跟进双边用户的使用反馈。', 1, 1, 1),
  (9010, 9002, 'HR 招聘专员', '北京', 8, 12, '招聘,沟通',
   '负责候选人沟通与面试安排，维护招聘渠道与人才库。', 1, 1, 1);