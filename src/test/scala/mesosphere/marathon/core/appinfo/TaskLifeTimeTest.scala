package mesosphere.marathon
package core.appinfo

import java.time.{OffsetDateTime, ZoneOffset}

import mesosphere.UnitTest
import mesosphere.marathon.core.appinfo.impl.TaskForStatistics
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder, TestTaskBuilder}
import mesosphere.marathon.state.{AbsolutePathId, Timestamp, UnreachableStrategy}

class TaskLifeTimeTest extends UnitTest {
  private[this] val now: Timestamp = Timestamp(OffsetDateTime.of(2015, 4, 9, 12, 30, 0, 0, ZoneOffset.UTC))
  private[this] val runSpecId = AbsolutePathId("/test")
  private[this] val agentInfo = AgentInfo(host = "host", agentId = Some("agent"), region = None, zone = None, attributes = Nil)
  private[this] def newInstanceId(): Instance.Id = Instance.Id.forRunSpec(runSpecId)

  private[this] def stagedInstance(): Instance = {
    TestInstanceBuilder.fromTask(TestTaskBuilder.Helper.stagedTask(newInstanceId()), agentInfo, UnreachableStrategy.default())
  }

  private[this] def runningInstanceWithLifeTime(lifeTimeSeconds: Double): Instance = {
    TestInstanceBuilder.fromTask(
      TestTaskBuilder.Helper.runningTask(newInstanceId(), startedAt = (now.millis - lifeTimeSeconds * 1000.0).round),
      agentInfo,
      UnreachableStrategy.default()
    )
  }

  private[this] def taskLifeTimeforSomeTasks(now: Timestamp, instances: Seq[Instance]): Option[raml.TaskLifeTime] = {
    TaskLifeTime.forSomeTasks(TaskForStatistics.forInstances(now, instances, Map.empty))
  }

  "TaskLifetime" should {
    "life time for no tasks" in {
      Given("no tasks")
      When("calculating life times")
      val lifeTimes = taskLifeTimeforSomeTasks(now, Seq.empty)
      Then("we get none")
      lifeTimes should be(None)
    }

    "life time only for tasks which have not yet been running" in {
      Given("not yet running instances")
      val instances = (1 to 3).map(_ => stagedInstance())
      When("calculating life times")
      val lifeTimes = taskLifeTimeforSomeTasks(now, instances)
      Then("we get none")
      lifeTimes should be(None)
    }

    "life times for task with life times" in {
      Given("three instances with the life times 2s, 4s, 9s")
      val instances = Seq(2.0, 4.0, 9.0).map(runningInstanceWithLifeTime)
      When("calculating life times")
      val lifeTimes = taskLifeTimeforSomeTasks(now, instances)
      Then("we get the correct stats")
      lifeTimes should be(
        Some(
          raml.TaskLifeTime(
            averageSeconds = 5.0,
            medianSeconds = 4.0
          )
        )
      )
    }

    "life times for task with life times ignore not yet running tasks" in {
      Given("three instances with the life times 2s, 4s, 9s")
      val instances = Seq(2.0, 4.0, 9.0).map(runningInstanceWithLifeTime) ++ Seq(stagedInstance())
      When("calculating life times")
      val lifeTimes = taskLifeTimeforSomeTasks(now, instances)
      Then("we get the correct stats")
      lifeTimes should be(
        Some(
          raml.TaskLifeTime(
            averageSeconds = 5.0,
            medianSeconds = 4.0
          )
        )
      )
    }
  }
}
