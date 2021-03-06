package com.weibo.dip.scheduler.service;

import com.caucho.hessian.server.HessianServlet;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DockerClientBuilder;
import com.google.common.base.Preconditions;
import com.weibo.dip.data.platform.commons.util.GsonUtil;
import com.weibo.dip.data.platform.commons.util.IPUtil;
import com.weibo.dip.scheduler.bean.Application;
import com.weibo.dip.scheduler.bean.ApplicationDependency;
import com.weibo.dip.scheduler.bean.ApplicationRecord;
import com.weibo.dip.scheduler.bean.ApplicationState;
import com.weibo.dip.scheduler.bean.ScheduleApplication;
import com.weibo.dip.scheduler.db.SchedulerOperator;
import com.weibo.dip.scheduler.quartz.QuartzJob;
import com.weibo.dip.scheduler.queue.Message;
import com.weibo.dip.scheduler.queue.MessageQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.TriggerUtils;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.spi.OperableTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduler Service Impl.
 *
 * @author yurun
 */
public class SchedulerServiceImpl extends HessianServlet implements SchedulerService {
  private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerServiceImpl.class);

  private MessageQueue queue;
  private Scheduler scheduler;
  private SchedulerOperator operator;

  /**
   * Construct a instance.
   *
   * @param queue MessageQueue
   * @param scheduler Scheduler
   * @param operator SchedulerOperator
   */
  public SchedulerServiceImpl(MessageQueue queue, Scheduler scheduler, SchedulerOperator operator) {
    this.queue = queue;
    this.scheduler = scheduler;
    this.operator = operator;
  }

  @Override
  public boolean connect() {
    return true;
  }

  @Override
  public void start(Application application) throws Exception {
    Preconditions.checkState(Objects.nonNull(application), "Application must be specifiled");

    Preconditions.checkState(
        !operator.existApplication(application.getName(), application.getQueue()),
        "Application %s already started",
        application.getUniqeName());

    // create jobdetail
    JobKey jobKey = new JobKey(application.getName(), application.getQueue());

    Preconditions.checkState(
        !scheduler.checkExists(jobKey),
        "Application %s does not in db, but exist in scheduler",
        application.getUniqeName());

    JobDataMap data = new JobDataMap();

    data.put(QuartzJob.NAME, application.getName());
    data.put(QuartzJob.QUEUE, application.getQueue());

    JobDetail job =
        JobBuilder.newJob(QuartzJob.class).withIdentity(jobKey).usingJobData(data).build();

    // create trigger
    TriggerKey triggerKey = new TriggerKey(application.getName(), application.getQueue());

    Trigger trigger =
        TriggerBuilder.newTrigger()
            .withIdentity(triggerKey)
            .withSchedule(
                CronScheduleBuilder.cronSchedule(application.getCron())
                    .withMisfireHandlingInstructionDoNothing())
            .build();

    // schedule and add
    scheduler.scheduleJob(job, trigger);

    operator.addApplication(application);

    LOGGER.info(
        "Application {}:{} started: {}",
        application.getName(),
        application.getQueue(),
        application.getCron());
  }

  @Override
  public void update(Application application) throws Exception {
    JobKey jobKey = new JobKey(application.getName(), application.getQueue());

    Preconditions.checkState(
        scheduler.checkExists(jobKey),
        "Application %s:%s does not exist",
        application.getName(),
        application.getQueue());

    Application oldApplication =
        operator.getApplication(application.getName(), application.getQueue());

    // cron can't be modified
    application.setCron(oldApplication.getCron());

    operator.updateApplication(application);
  }

  @Override
  public void stop(String name, String queue) throws Exception {
    Preconditions.checkState(
        StringUtils.isNotEmpty(name) && StringUtils.isNotEmpty(queue),
        "name and queue must be specified");

    Preconditions.checkState(
        operator.existApplication(name, queue), "Application %s_%s does not exist", name, queue);

    JobKey jobKey = new JobKey(name, queue);
    TriggerKey triggerKey = new TriggerKey(name, queue);

    scheduler.unscheduleJob(triggerKey);
    scheduler.deleteJob(jobKey);

    operator.deleteApplication(name, queue);

    LOGGER.info("Application {}:{} stoped", name, queue);
  }

  @Override
  public Application get(String name, String queue) throws Exception {
    return operator.getApplication(name, queue);
  }

  @Override
  public List<String> queues() throws Exception {
    return scheduler.getJobGroupNames();
  }

  @Override
  public List<Application> list() throws Exception {
    List<String> queues = queues();
    if (CollectionUtils.isEmpty(queues)) {
      return null;
    }

    List<Application> applications = new ArrayList<>();

    for (String queue : queues) {
      List<Application> apps = list(queue);
      if (CollectionUtils.isNotEmpty(apps)) {
        applications.addAll(apps);
      }
    }

    return applications;
  }

  @Override
  public List<Application> list(String queue) throws Exception {
    Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.groupEquals(queue));
    if (CollectionUtils.isEmpty(jobKeys)) {
      return null;
    }

    List<Application> applications = new ArrayList<>();

    for (JobKey jobKey : jobKeys) {
      applications.add(operator.getApplication(jobKey.getName(), jobKey.getGroup()));
    }

    return applications;
  }

  @Override
  public List<ApplicationRecord> listRunning(String queue) throws Exception {
    return operator.getRunningApplicationRecords(queue);
  }

  @Override
  public void addDependency(ApplicationDependency dependency) throws Exception {
    Preconditions.checkState(
        Objects.nonNull(dependency), "Application dependency must be specified");
    Preconditions.checkState(
        operator.existApplication(dependency.getName(), dependency.getQueue()),
        "Application %s_%s does not exist",
        dependency.getName(),
        dependency.getQueue());
    Preconditions.checkState(
        operator.existApplication(dependency.getDependName(), dependency.getDependQueue()),
        "Depend application %s_%s does not exist",
        dependency.getDependName(),
        dependency.getDependQueue());

    operator.addOrUpdateApplicationDependency(dependency);

    LOGGER.info(
        "Application {}_{} add dependency {}_{} with [{}, {}]",
        dependency.getName(),
        dependency.getQueue(),
        dependency.getDependName(),
        dependency.getDependQueue(),
        dependency.getFromSeconds(),
        dependency.getToSeconds());
  }

  @Override
  public void removeDependency(String name, String queue, String dependName, String dependQueue)
      throws Exception {
    Preconditions.checkState(
        operator.existApplication(name, queue), "Application %s_%s does not exist", name, queue);
    Preconditions.checkState(
        operator.existApplication(dependName, dependQueue),
        "Depend application %s_%s does not exist",
        name,
        queue);

    operator.deleteApplicationDependency(name, queue, dependName, dependQueue);

    LOGGER.info("Application {}_{} remove dependency {}_{}", name, queue, dependName, dependQueue);
  }

  @Override
  public List<ApplicationDependency> getDependencies(String name, String queue) throws Exception {
    return operator.getDependencies(name, queue);
  }

  @Override
  public List<Message> queued() throws Exception {
    return queue.listQueued();
  }

  @Override
  public ApplicationRecord getRecord(String name, String queue, Date scheduleTime)
      throws Exception {
    return operator.getApplicationRecord(name, queue, scheduleTime);
  }

  @Override
  public List<ApplicationRecord> listRecords(
      String name, String queue, Date beginTime, Date endTime) throws Exception {
    return operator.getApplicationRecords(name, queue, beginTime, endTime);
  }

  @Override
  public List<ScheduleApplication> repair(String name, String queue, Date beginTime, Date endTime)
      throws Exception {
    JobKey jobKey = new JobKey(name, queue);

    Preconditions.checkState(
        scheduler.checkExists(jobKey), "Application %s:%s does not exist", name, queue);

    Application application = operator.getApplication(name, queue);

    List<Date> fireTimes =
        TriggerUtils.computeFireTimesBetween(
            (OperableTrigger)
                TriggerBuilder.newTrigger()
                    .withSchedule(CronScheduleBuilder.cronSchedule(application.getCron()))
                    .build(),
            null,
            beginTime, // include
            endTime); // include

    if (CollectionUtils.isEmpty(fireTimes)) {
      return null;
    }

    List<ScheduleApplication> scheduleApplications = new ArrayList<>();

    Date now = new Date();

    for (Date fireTime : fireTimes) {
      ApplicationRecord record = operator.getApplicationRecord(name, queue, fireTime);

      if (Objects.isNull(record) || !record.getState().equals(ApplicationState.SUCCESS)) {
        ApplicationRecord applicationRecord =
            new ApplicationRecord(
                application.getName(),
                application.getQueue(),
                IPUtil.getLocalhost(),
                fireTime,
                now,
                ApplicationState.QUEUED);

        ScheduleApplication scheduleApplication =
            new ScheduleApplication(application, applicationRecord);

        scheduleApplications.add(scheduleApplication);

        this.queue.produce(
            GsonUtil.toJson(scheduleApplication),
            scheduleApplication.getQueue(),
            scheduleApplication.getPriority(),
            now);
        operator.addOrUpdateApplicationRecord(applicationRecord);

        LOGGER.info("Application {} repaired", scheduleApplication.getUniqeName());
      }
    }

    return scheduleApplications;
  }

  @Override
  public boolean deleteQueued(int id) throws Exception {
    Message message = queue.get(id);
    if (Objects.isNull(message)) {
      return false;
    }

    boolean flag = false;

    if (queue.deleteQueued(id)) {
      ScheduleApplication scheduleApplication =
          GsonUtil.fromJson(message.getMessage(), ScheduleApplication.class);

      ApplicationRecord record = scheduleApplication.getApplicationRecord();
      record.setState(ApplicationState.KILLED);

      operator.addOrUpdateApplicationRecord(record);

      flag = true;
    }

    return flag;
  }

  @Override
  public boolean kill(String name, String queue, Date scheduleTime) throws Exception {
    ApplicationRecord record = operator.getApplicationRecord(name, queue, scheduleTime);
    if (Objects.isNull(record) || !record.getState().equals(ApplicationState.RUNNING)) {
      return false;
    }

    String containerName = record.getUniqeName();

    DockerClient dockerClient = DockerClientBuilder.getInstance().build();

    try {
      List<Container> containers =
          dockerClient
              .listContainersCmd()
              .withNameFilter(Collections.singleton(containerName))
              .exec();
      if (CollectionUtils.isEmpty(containers)) {
        return false;
      }

      dockerClient.killContainerCmd(containers.get(0).getId()).exec();
    } finally {
      dockerClient.close();
    }

    return true;
  }
}
