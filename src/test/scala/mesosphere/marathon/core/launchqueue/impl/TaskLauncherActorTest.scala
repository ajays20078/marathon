package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ ActorContext, ActorRef, ActorSystem, Cancellable, Props, Terminated }
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.flow.OfferReviver
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launcher.InstanceOpFactory
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedInstanceOps }
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.base.util.InstanceOpSourceDelegate.InstanceOpRejected
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.MarathonTaskStatusMapping
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, Protos }
import org.mockito
import org.mockito.Mockito
import org.scalatest.GivenWhenThen
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Also check LaunchQueueModuleTest which tests the interplay of the AppTaskLauncherActor
  * with the queue.
  *
  * In this class we specifically test:
  *
  * * tracking task status
  * * timeout for task launching feedback
  */
class TaskLauncherActorTest extends MarathonSpec with GivenWhenThen {
  import org.mockito.{ Matchers => m }

  test("Initial population of task list from taskTracker with one task") {
    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.finalInstanceCount == 1)
    assert(!counts.inProgress)
    assert(counts.instancesLeftToLaunch == 0)

    Mockito.verify(taskTracker).instancesBySpecSync
  }

  test("Upgrading an app updates app definition in actor and requeries backoff") {
    Given("an entry for an app")
    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))
    val launcherRef = createLauncherRef(instances = 3)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]
    assert(counts.instancesLeftToLaunch == 3)
    Mockito.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    Mockito.reset(offerMatcherManager)

    When("upgrading the app")
    val upgradedApp = f.app.copy(cmd = Some("new command"))
    launcherRef ! TaskLauncherActor.AddTasks(upgradedApp, 1)

    Then("the actor requeries the backoff delay")
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
    val newDelay: Timestamp = clock.now() + 5.seconds
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, newDelay))
    val counts2 = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]
    assert(counts2.backOffUntil == newDelay)

    And("the actor knows the new app definition")
    assert(counts2.runSpec == upgradedApp)
    And("resets the task to launch according to the new add command")
    assert(counts2.instancesLeftToLaunch == 1)

    And("removes its offer subscription because of the backoff delay")
    Mockito.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())

    // We don't care about these:
    Mockito.reset(taskTracker)
  }

  test("Upgrading an app updates reregisters the offerMatcher at the manager") {
    Given("an entry for an app")
    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))
    val launcherRef = createLauncherRef(instances = 1)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(f.app))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(f.app, clock.now()))
    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]
    assert(counts.instancesLeftToLaunch == 1)

    // We don't care about interactions until this point
    Mockito.reset(offerMatcherManager)

    When("upgrading the app")
    val upgradedApp = f.app.copy(cmd = Some("new command"))
    launcherRef ! TaskLauncherActor.AddTasks(upgradedApp, 1)
    rateLimiterActor.expectMsg(RateLimiterActor.GetDelay(upgradedApp))
    rateLimiterActor.reply(RateLimiterActor.DelayUpdate(upgradedApp, clock.now()))

    // wait for message being processed
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    Then("the actor reregisters itself for at the offerMatcher")
    val inOrder = Mockito.inOrder(offerMatcherManager)
    inOrder.verify(offerMatcherManager).removeSubscription(mockito.Matchers.any())(mockito.Matchers.any())
    inOrder.verify(offerMatcherManager).addSubscription(mockito.Matchers.any())(mockito.Matchers.any())

    // We don't care about these:
    Mockito.reset(taskTracker)
  }

  test("Process task launch") {
    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedInstanceOps]

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.finalInstanceCount == 2)
    assert(counts.inProgress)
    assert(counts.instancesLeftToLaunch == 0)

    Mockito.verify(taskTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Wait for inflight task launches on stop") {
    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matched =
      Await.result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedInstanceOps]

    val testProbe = TestProbe()
    testProbe.watch(launcherRef)

    launcherRef ! TaskLauncherActor.Stop
    Await.result(launcherRef ? "waitingForInFlight", 3.seconds)
    matched.opsWithSource.foreach(_.reject("stuff"))
    testProbe.expectMsgClass(classOf[Terminated])

    Mockito.verify(taskTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Process task launch reject") {
    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matchedTasks =
      Await
        .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedInstanceOps]
    matchedTasks.opsWithSource.foreach(_.reject("stuff"))

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.instancesLeftToLaunch == 1)

    assert(counts.inProgress)
    assert(counts.finalInstanceCount == 1)
    assert(counts.instancesLeftToLaunch == 1)

    Mockito.verify(taskTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Process task launch timeout") {
    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    var scheduleCalled = false
    val props = Props(
      new TaskLauncherActor(
        launchQueueConfig,
        offerMatcherManager, clock, taskOpFactory,
        maybeOfferReviver = None,
        taskTracker, rateLimiterActor.ref,
        f.app, instancesToLaunch = 1
      ) {
        override protected def scheduleTaskOperationTimeout(
          context: ActorContext, message: InstanceOpRejected): Cancellable = {
          scheduleCalled = true
          mock[Cancellable]
        }
      }
    )
    val launcherRef = actorSystem.actorOf(props, "launcher")

    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    Await
      .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedInstanceOps]

    // just make sure that prior messages have been processed, will not launch further tasks

    Await
      .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
      .asInstanceOf[MatchedInstanceOps]

    assert(scheduleCalled)

    Mockito.verify(taskTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Process task launch accept") {
    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.empty)
    val offer = MarathonTestHelper.makeBasicOffer().build()
    Mockito.when(taskOpFactory.buildTaskOp(m.any())).thenReturn(Some(f.launch(f.task, f.marathonTask)))

    val launcherRef = createLauncherRef(instances = 1)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    val matchedTasks =
      Await
        .result(launcherRef ? ActorOfferMatcher.MatchOffer(clock.now() + 1.seconds, offer), 3.seconds)
        .asInstanceOf[MatchedInstanceOps]
    matchedTasks.opsWithSource.foreach(_.accept())

    val counts = Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.finalInstanceCount == 1)
    assert(!counts.inProgress)
    assert(counts.instancesLeftToLaunch == 0)
    assert(counts.instancesLeftToLaunch == 0)

    Mockito.verify(taskTracker).instancesBySpecSync
    val matchRequest = InstanceOpFactory.Request(f.app, offer, Iterable.empty, additionalLaunches = 1)
    Mockito.verify(taskOpFactory).buildTaskOp(matchRequest)
  }

  test("Expunged task is removed from counts") {
    val update = TaskStatusUpdateTestHelper.finished(f.marathonTask).wrapped
    val expectedCounts = QueuedInstanceInfo(f.app, inProgress = false, 0, 0, 0, Timestamp(0))

    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    // wait for startup
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    // task status update
    val counts = Await.result(launcherRef ? update, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.instancesLeftToLaunch == expectedCounts.instancesLeftToLaunch)
    assert(counts.finalInstanceCount == expectedCounts.finalInstanceCount)

    Mockito.verify(taskTracker).instancesBySpecSync
  }

  for (
    update <- MarathonTaskStatusMapping.Gone.toSeq.map(reason => TaskStatusUpdateTestHelper.lost(reason, f.marathonTask))
      .union(Seq(
        TaskStatusUpdateTestHelper.finished(f.marathonTask),
        TaskStatusUpdateTestHelper.killed(f.marathonTask),
        TaskStatusUpdateTestHelper.error(f.marathonTask)))
  ) {
    test(s"Terminated task (${update.simpleName} with ${update.reason} is removed") {
      Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      // task status update
      val counts = Await.result(launcherRef ? update.wrapped, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      assert(counts.unreachableInstances == 0)

      assert(!counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)

      Mockito.verify(taskTracker, Mockito.timeout(8000)).instancesBySpecSync
    }
  }

  val log = LoggerFactory.getLogger(getClass)
  for (
    update <- MarathonTaskStatusMapping.Unreachable.map(r => TaskStatusUpdateTestHelper.lost(r, f.marathonTask))
  ) {
    test(s"TemporarilyUnreachable task (${update.simpleName} with ${update.reason} is NOT removed") {
      Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      // task status update
      val counts = Await.result(launcherRef ? update.wrapped, 3.seconds).asInstanceOf[QueuedInstanceInfo]
      assert(!counts.inProgress)
      assert(counts.finalInstanceCount == 1)
      assert(counts.unreachableInstances == 1)
      assert(counts.instancesLeftToLaunch == 0)

      Mockito.verify(taskTracker).instancesBySpecSync
    }
  }

  test("Updated task is reflected in counts") {
    val update = TaskStatusUpdateTestHelper.runningHealthy(f.marathonTask)
    val expectedCounts = QueuedInstanceInfo(f.app, inProgress = false, 0, 1, 0, Timestamp(0))

    Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))

    val launcherRef = createLauncherRef(instances = 0)
    launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

    // wait for startup
    Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    // task status update
    val counts = Await.result(launcherRef ? update.wrapped, 3.seconds).asInstanceOf[QueuedInstanceInfo]

    assert(counts.instancesLeftToLaunch == expectedCounts.instancesLeftToLaunch)
    assert(counts.finalInstanceCount == expectedCounts.finalInstanceCount)

    Mockito.verify(taskTracker).instancesBySpecSync
  }

  for (
    update <- MarathonTaskStatusMapping.Gone.toSeq.map(r => TaskStatusUpdateTestHelper.lost(r, f.marathonTask))
      .union(Seq(
        TaskStatusUpdateTestHelper.finished(f.marathonTask),
        TaskStatusUpdateTestHelper.killed(f.marathonTask),
        TaskStatusUpdateTestHelper.error(f.marathonTask)))
  ) {
    test(s"Revive offers if task with constraints terminates (${update.simpleName} with ${update.reason})") {
      Given("an actor for an app with constraints and one task")
      val constraint = Protos.Constraint
        .newBuilder()
        .setField("test")
        .setOperator(Protos.Constraint.Operator.CLUSTER)
        .build()
      val appWithConstraints = f.app.copy(constraints = Set(constraint))
      Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))

      val launcherRef = createLauncherRef(instances = 0, appToLaunch = appWithConstraints)
      launcherRef ! RateLimiterActor.DelayUpdate(appWithConstraints, clock.now())

      And("that has successfully started up")
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      When("we get a status update about a terminated task")
      val counts = Await.result(launcherRef ? update.wrapped, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      Then("reviveOffers has been called")
      Mockito.verify(offerReviver).reviveOffers()

      And("the task tracker as well")
      Mockito.verify(taskTracker).instancesBySpecSync
    }
  }

  for (
    update <- Seq(
      TaskStatusUpdateTestHelper.staging(f.marathonTask),
      TaskStatusUpdateTestHelper.running(f.marathonTask)
    )
  ) {
    test(s"DO NOT REMOVE running task (${update.simpleName})") {
      Mockito.when(taskTracker.instancesBySpecSync).thenReturn(InstanceTracker.InstancesBySpec.forTasks(f.marathonTask))

      val launcherRef = createLauncherRef(instances = 0)
      launcherRef ! RateLimiterActor.DelayUpdate(f.app, clock.now())

      // wait for startup
      Await.result(launcherRef ? TaskLauncherActor.GetCount, 3.seconds).asInstanceOf[QueuedInstanceInfo]

      // task status update
      val counts = Await.result(
        launcherRef ? update.wrapped,
        3.seconds
      ).asInstanceOf[QueuedInstanceInfo]

      assert(counts.finalInstanceCount == 1)
      assert(!counts.inProgress)
      assert(counts.instancesLeftToLaunch == 0)

      Mockito.verify(taskTracker).instancesBySpecSync
    }
  }

  object f {
    import org.apache.mesos.{ Protos => Mesos }
    val launch = new InstanceOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(_: Mesos.TaskInfo, _: Task.LaunchedEphemeral)
    val app = AppDefinition(id = PathId("/testapp"))
    val taskId = Instance.Id.forRunSpec(app.id)
    val task = MarathonTestHelper.makeOneCPUTask(taskId.idString).build()
    val marathonTask = MarathonTestHelper.mininimalTask(task.getTaskId.getValue).copy(
      runSpecVersion = app.version, status = Task.Status(app.version, None, None, taskStatus = InstanceStatus.Running), hostPorts = Seq.empty)
  }

  private[this] implicit val timeout: Timeout = 3.seconds
  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var launchQueueConfig: LaunchQueueConfig = _
  private[this] var offerMatcherManager: OfferMatcherManager = _
  private[this] var clock: ConstantClock = _
  private[this] var taskOpFactory: InstanceOpFactory = _
  private[this] var taskTracker: InstanceTracker = _
  private[this] var offerReviver: OfferReviver = _
  private[this] var rateLimiterActor: TestProbe = _

  private[this] def createLauncherRef(instances: Int, appToLaunch: AppDefinition = f.app): ActorRef = {
    val props = TaskLauncherActor.props(
      launchQueueConfig,
      offerMatcherManager, clock, taskOpFactory,
      maybeOfferReviver = Some(offerReviver),
      taskTracker, rateLimiterActor.ref) _
    actorSystem.actorOf(
      props(appToLaunch, instances),
      "launcher"
    )
  }

  before {
    actorSystem = ActorSystem()
    offerMatcherManager = mock[OfferMatcherManager]
    launchQueueConfig = new LaunchQueueConfig {
      verify()
    }
    clock = ConstantClock()
    taskOpFactory = mock[InstanceOpFactory]
    taskTracker = mock[InstanceTracker]
    offerReviver = mock[OfferReviver]
    rateLimiterActor = TestProbe()
  }

  after {
    // we are not interested in these. We check for these in LaunchQueueModuleTest
    // Mockito.verifyNoMoreInteractions(offerMatcherManager)
    Mockito.verifyNoMoreInteractions(taskOpFactory)
    //    Mockito.verifyNoMoreInteractions(taskTracker)
    Await.result(actorSystem.terminate(), Duration.Inf)
  }
}