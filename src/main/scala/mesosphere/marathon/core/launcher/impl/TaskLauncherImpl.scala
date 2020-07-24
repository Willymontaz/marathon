package mesosphere.marathon
package core.launcher.impl

import java.util.Collections

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.launcher.{InstanceOp, TaskLauncher}
import mesosphere.marathon.metrics.Metrics
import org.apache.mesos.Protos.Resource.RevocableInfo

import scala.jdk.CollectionConverters._
import org.apache.mesos.Protos.{Offer, OfferID, Resource, Status, TaskGroupInfo, TaskInfo}
import org.apache.mesos.{Protos, SchedulerDriver}

private[launcher] class TaskLauncherImpl(metrics: Metrics, marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder)
    extends TaskLauncher
    with StrictLogging {

  private[this] val usedOffersMetric =
    metrics.counter("mesos.offers.used")
  private[this] val launchedTasksMetric =
    metrics.counter("tasks.launched")
  private[this] val declinedOffersMetric =
    metrics.counter("mesos.offers.declined")

  private val consideredRevocableOffers = Array("cpus", "mem")

  override def acceptOffer(offerID: OfferID, taskOps: Seq[InstanceOp]): Boolean = {
    val accepted = withDriver(s"launchTasks($offerID)") { driver =>
      //We accept the offer, the rest of the offer is declined automatically with the given filter.
      //The filter duration is set to 0, so we get the same offer in the next allocator cycle.
      val noFilter = Protos.Filters.newBuilder().setRefuseSeconds(0).build()
      val operations = taskOps.flatMap(_.offerOperations)

      def resourceRevocable(resource: Resource): Resource = {
        val builder = resource.toBuilder
        if (consideredRevocableOffers.contains(resource.getName))
          builder.setRevocable(RevocableInfo.newBuilder().build())
        builder.build()
      }

      def taskInfoWithRevocable(taskInfo: TaskInfo): TaskInfo = {
        val builder = taskInfo.toBuilder.clearResources()
        taskInfo.getResourcesList.forEach(res => {
          builder.addResources(resourceRevocable(res))
        })
        builder.build()
      }

      def launchWithRevocable(launch: Offer.Operation.Launch): Offer.Operation.Launch = {
        val builder = launch.toBuilder.clearTaskInfos()
        launch.getTaskInfosList.forEach(taskInfo => {
          builder.addTaskInfos(taskInfoWithRevocable(taskInfo))
        })
        builder.build()
      }

      def taskGroupInfoWithRevocable(taskGroupInfo: TaskGroupInfo): TaskGroupInfo = {
        val builder = taskGroupInfo.toBuilder.clearTasks()
        taskGroupInfo.getTasksList.forEach(taskInfo => {
          builder.addTasks(taskInfoWithRevocable(taskInfo))
        })
        builder.build()
      }

      def launchGroupWithRevocable(launchGroup: Offer.Operation.LaunchGroup): Offer.Operation.LaunchGroup = {
        val builder = launchGroup.toBuilder.clearTaskGroup()
        builder.setTaskGroup(taskGroupInfoWithRevocable(launchGroup.getTaskGroup))
        builder.build()
      }

      def operationWithRevocable(op: Offer.Operation): Offer.Operation = {
        val builder = op.toBuilder.clearLaunch().clearLaunchGroup()
        if(op.hasLaunch)
          builder.setLaunch(launchWithRevocable(op.getLaunch))
        if(op.hasLaunchGroup)
          builder.setLaunchGroup(launchGroupWithRevocable(op.getLaunchGroup))
        builder.build()
      }

      val revocableOperations = operations.map(operationWithRevocable)

      logger.info(s" --- WMO --- Operations on $offerID:\n${revocableOperations.mkString("\n")}")

      driver.acceptOffers(Collections.singleton(offerID), revocableOperations.asJava, noFilter)
    }
    if (accepted) {
      usedOffersMetric.increment()
      val launchCount = taskOps.count {
        case _: InstanceOp.LaunchTask => true
        case _: InstanceOp.LaunchTaskGroup => true
        case _ => false
      }
      launchedTasksMetric.increment(launchCount.toLong)
    }
    accepted
  }

  override def declineOffer(offerID: OfferID, refuseMilliseconds: Option[Long]): Unit = {
    val declined = withDriver(s"declineOffer(${offerID.getValue})") {
      val filters = refuseMilliseconds
        .map(seconds => Protos.Filters.newBuilder().setRefuseSeconds(seconds / 1000.0).build())
        .getOrElse(Protos.Filters.getDefaultInstance)
      _.declineOffer(offerID, filters)
    }
    if (declined) {
      declinedOffersMetric.increment()
    }
  }

  private[this] def withDriver(description: => String)(block: SchedulerDriver => Status): Boolean = {
    marathonSchedulerDriverHolder.driver match {
      case Some(driver) =>
        val status = block(driver)
        logger.debug(s"$description returned status = $status")

        status == Status.DRIVER_RUNNING

      case None =>
        logger.warn(s"Cannot execute '$description', no driver available")
        false
    }
  }
}
