package com.weibo.dip.scheduler.client;

import com.caucho.hessian.client.HessianProxyFactory;
import com.weibo.dip.scheduler.bean.Application;
import com.weibo.dip.scheduler.bean.ApplicationDependency;
import com.weibo.dip.scheduler.bean.ApplicationRecord;
import com.weibo.dip.scheduler.bean.ScheduleApplication;
import com.weibo.dip.scheduler.queue.Message;
import com.weibo.dip.scheduler.service.SchedulerService;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduler client.
 *
 * @author yurun
 */
public class SchedulerClient implements SchedulerService {
  private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerClient.class);

  private SchedulerService schedulerService;

  /**
   * Construct a scheduler client instance.
   *
   * @param host scheduler server hostname
   * @param port scheduler server port
   * @throws Exception if create proxy error
   */
  public SchedulerClient(String host, int port) throws Exception {
    HessianProxyFactory proxyFactory = new HessianProxyFactory();

    proxyFactory.setOverloadEnabled(true);

    schedulerService =
        (SchedulerService)
            proxyFactory.create(
                SchedulerService.class, "http://" + host + ":" + port + "/service/scheduler");
  }

  @Override
  public boolean connect() {
    return schedulerService.connect();
  }

  @Override
  public void start(Application application) throws Exception {
    schedulerService.start(application);
  }

  @Override
  public void update(Application application) throws Exception {
    schedulerService.update(application);
  }

  @Override
  public void stop(String name, String queue) throws Exception {
    schedulerService.stop(name, queue);
  }

  @Override
  public Application get(String name, String queue) throws Exception {
    return schedulerService.get(name, queue);
  }

  @Override
  public List<String> queues() throws Exception {
    return schedulerService.queues();
  }

  @Override
  public List<Application> list() throws Exception {
    return schedulerService.list();
  }

  @Override
  public List<Application> list(String queue) throws Exception {
    return schedulerService.list(queue);
  }

  @Override
  public List<ApplicationRecord> listRunning(String queue) throws Exception {
    return schedulerService.listRunning(queue);
  }

  @Override
  public void addDependency(ApplicationDependency dependency) throws Exception {
    schedulerService.addDependency(dependency);
  }

  @Override
  public void removeDependency(String name, String queue, String dependName, String dependQueue)
      throws Exception {
    schedulerService.removeDependency(name, queue, dependName, dependQueue);
  }

  @Override
  public List<ApplicationDependency> getDependencies(String name, String queue) throws Exception {
    return schedulerService.getDependencies(name, queue);
  }

  @Override
  public List<Message> queued() throws Exception {
    return schedulerService.queued();
  }

  @Override
  public ApplicationRecord getRecord(String name, String queue, Date scheduleTime)
      throws Exception {
    return schedulerService.getRecord(name, queue, scheduleTime);
  }

  @Override
  public List<ApplicationRecord> listRecords(
      String name, String queue, Date beginTime, Date endTime) throws Exception {
    return schedulerService.listRecords(name, queue, beginTime, endTime);
  }

  @Override
  public List<ScheduleApplication> repair(String name, String queue, Date beginTime, Date endTime)
      throws Exception {
    return schedulerService.repair(name, queue, beginTime, endTime);
  }

  @Override
  public boolean deleteQueued(int id) throws Exception {
    return schedulerService.deleteQueued(id);
  }

  @Override
  public boolean kill(String name, String queue, Date scheduleTime) throws Exception {
    return schedulerService.kill(name, queue, scheduleTime);
  }
}
