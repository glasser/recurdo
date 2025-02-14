package net.davidglasser.recurdo

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.SystemExitException
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import io.ktor.client.HttpClient
import io.ktor.client.features.defaultRequest
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeParseException

fun ArgParser.DelegateProvider<String>.defaultToEnvVar(envVarName: String): ArgParser.DelegateProvider<String> =
  System.getenv(envVarName)?.let {
    default(it)
  } ?: this

const val LABEL_PREFIX = "recur_"

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnyLabel(val name: String) {
  fun recurLabel(): RecurLabel? = name.takeIf { it.startsWith(LABEL_PREFIX) }?.let {
    try {
      RecurLabel(name = name, period = Period.parse(it.removePrefix(LABEL_PREFIX)))
    } catch (t: DateTimeParseException) {
      throw SystemExitException("Bad label name $it", 1)
    }
  }
}

data class RecurLabel(val name: String, val period: Period)

data class Due(
  val isRecurring: Boolean,
  val string: String,
  val date: LocalDate,
  val datetime: Instant?,
  val timezone: String?,
  val lang: String
)

data class Deadline(
  val date: LocalDate,
  val lang: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Task(
  val id: String,
  val projectID: String,
  val sectionID: String?,
  val content: String,
  val labels: List<String>,  // names, not IDs
  val parentID: String?,
  val order: Int,
  val priority: Int,
  val due: Due?,
  val deadline: Deadline?,
  val url: String
) {
  fun asNewTask(period: Period, newParentID: String?) = NewTask(
    projectID = projectID,
    sectionID = sectionID,
    content = content,
    labels = labels,
    parentID = newParentID,
    order = order,
    priority = priority,
    dueDate = due?.let { it.date + period },
    deadlineDate = deadline?.let { it.date + period }
  )
}

data class NewTask(
  val projectID: String,
  val sectionID: String?,
  val content: String,
  val labels: List<String>,
  val parentID: String?,
  val order: Int,
  val priority: Int,
  val dueDate: LocalDate?,
  val deadlineDate: LocalDate?
)

class TreeTask(
  val task: Task,
  val children: List<TreeTask>
) {
  fun collectWithDescendants(c: MutableCollection<TreeTask>) {
    c.add(this)
    collectDescendants(c)
  }

  private fun collectDescendants(c: MutableCollection<TreeTask>) {
    children.forEach { it.collectWithDescendants(c) }
  }

  @OptIn(ExperimentalStdlibApi::class)
  fun descendants() = buildList { collectDescendants(this) }

  @OptIn(ExperimentalStdlibApi::class)
  fun withDescendants() = buildList { collectWithDescendants(this) }

  // No 'parent' since that would be infinite recursion.
  override fun toString() = "TreeTask(task=$task, children=$children)"
}

@OptIn(ExperimentalStdlibApi::class)
fun Iterable<Task>.asTreeTasks(): List<TreeTask> {
  val originalTasksByParentID = groupBy { it.parentID }.withDefault { emptyList() }
  fun treeify(task: Task): TreeTask = TreeTask(
    task = task,
    children = originalTasksByParentID
      .getValue(task.id)  // always non-null other than when creating
      .sortedBy { it.order }
      .map { treeify(it) }
  )
  // Order is not relevant for root tasks.
  val rootTreeTasks = originalTasksByParentID.getValue(null).map { treeify(it) }
  return buildList {
    rootTreeTasks.forEach {
      it.collectWithDescendants(this)
    }
  }
}


class TodoistClient(private val httpClient: HttpClient) {
  private fun HttpRequestBuilder.apiPath(vararg components: String) {
    url {
      path(listOf("rest", "v2") + components)
    }
  }

  private suspend fun getAllLabels(): List<AnyLabel> = httpClient.get {
    apiPath("labels")
  }

  suspend fun getRecurLabels() = getAllLabels().mapNotNull { it.recurLabel() }

  suspend fun getAllTasks(): List<TreeTask> = httpClient.get<List<Task>> {
    apiPath("tasks")
  }.asTreeTasks()

  suspend fun createTask(newTask: NewTask): Task = httpClient.post {
    apiPath("tasks")
    contentType(ContentType.Application.Json)
    body = newTask
  }

  suspend fun setLabels(task: Task, labels: List<String>): Unit = httpClient.post {
    apiPath("tasks", task.id.toString())
    contentType(ContentType.Application.Json)
    body = mapOf("labels" to labels)
  }
}

suspend fun copyTree(client: TodoistClient, recurLabel: RecurLabel, task: TreeTask, overrideParentID: String?) {
  val copiedTask = client.createTask(task.task.asNewTask(recurLabel.period, overrideParentID ?: task.task.parentID))
  task.children.forEach { child ->
    copyTree(client, recurLabel, child, copiedTask.id)
  }
}

suspend fun removeLabel(client: TodoistClient, recurLabel: RecurLabel, t: TreeTask) {
  client.setLabels(t.task, t.task.labels.filter { it != recurLabel.name })
}

class Args(parser: ArgParser) {
  val apiKey by parser.storing("Todoist API Key").defaultToEnvVar("API_KEY")
  val cutoffPeriod: Period by parser.storing("ISO 8601 period defining how far in the future labeled tasks must be to not be processed") {
    Period.parse(this)
  }.default(Period.parse("P4M"))
}

fun main(argv: Array<String>) = mainBody {
  runBlocking(Dispatchers.Default) {
    val args = ArgParser(argv).parseInto(::Args)
    val httpClient = HttpClient {
      defaultRequest {
        url {
          protocol = URLProtocol.HTTPS
          host = "api.todoist.com"
        }
        header("Authorization", "Bearer ${args.apiKey}")
      }
      install(JsonFeature) {
        serializer = JacksonSerializer {
          registerModule(JavaTimeModule())
          // Write dates as strings.
          disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          propertyNamingStrategy = PropertyNamingStrategy.SNAKE_CASE
        }
      }
    }
    // We use LocalDateTime rather than Instant because that's what lets you add
    // ill-defined periods like months and years.
    val cutoff: LocalDate = LocalDate.now() + args.cutoffPeriod
    println("Updating all tasks due before $cutoff")
    val client = TodoistClient(httpClient)

    // Instead of having special logic to process a single task multiple times
    // until it passes cutoff, we just run the whole program logic repeatedly
    // until it does nothing.
    while (true) {
      if (process(client, cutoff) == 0) {
        break
      }
    }
  }
}

private suspend fun process(client: TodoistClient, cutoff: LocalDate): Int {
  val recurLabelsByName = client.getRecurLabels().associateBy { it.name }
  return client.getAllTasks().filter { t ->
    t.task.labels.any { it in recurLabelsByName }
  }.map { t ->
    // First validate all the tasks (even those that aren't ready yet)
    checkNotNull(t.task.due) { "Labeled task has no due date: $t" }

    val recurLabel = t.task.labels.mapNotNull { recurLabelsByName[it] }.also {
      check(it.size == 1) { "Task has multiple recur labels: $t" }
    }.single()

    t.descendants().forEach { node ->
      check(node.task.labels.none { it in recurLabelsByName }) {
        "Task with recur label nested under another one: $node under $t"
      }
    }
    t.withDescendants().forEach { node ->
      node.task.due?.let { due ->
        check(!due.isRecurring) { "Task under labeled task cannot be recurring: $node" }
        // just don't want to bother thinking about handling date and datetime separately.
        check(due.datetime == null) { "Task under labeled task cannot have a specific time of day: $node" }
      }
    }

    t to recurLabel
  }.filter { (t, _) ->
    // ignore the time for now
    t.task.due!!.date < cutoff
  }.map { (t, recurLabel) ->
    println("Processing ${t.task.content} @ ${t.task.due!!.date} (${t.task.url})")
    copyTree(client, recurLabel, t, null)
    removeLabel(client, recurLabel, t)
    Unit
  }
    .size
}
