# recurdo: simulated recurring tasks for Todoist

I love [Todoist](https://todoist.com/)'s UI and most of its data model, but
modeling recurring tasks with start and due dates like bills is [a bit of a
pain](https://www.reddit.com/r/todoist/comments/gtht2g/simulating_start_dates_for_recurring_tasks/).

This project takes an alternate approach. As an alternative to using Todoist's
own recurring task feature, you can set a label with a name like `recur_1M` on a
task and run this program. It will find all tasks with labels of that form and
make a copy of them and any descendant tasks, adding one month to all due
dates. It will then remove the label from the original task. It will do this
repeatedly until all tasks with `recur_*` labels are farther in the future than
the specified cutoff period (4 months by default).

## Building and running

Just requires a Java installation of some kind.

```
$ ./gradlew build
$ ./bin/recurdo --help
```

