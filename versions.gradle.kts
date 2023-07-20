mapOf(
  "junit_androidx_version" to "1.1.5",
  "junit_version" to "4.13.2",
  "moshi_version" to "1.13.0",
  "lifecycle_version" to "2.5.1",
  "mockk_version" to "1.13.3",
  "coroutines_test_version" to "1.6.4",
  "robolectric_version" to "4.10.3",
  "mockwebserver_version" to "4.10.0",
  "google_truth_version" to "1.1.5",
  "startup_version" to "1.1.1",
  "okhttp_version" to "4.10.0",
  "kotlinx_date_version" to "0.4.0"
).forEach { (name, version) ->
  project.extra.set(name, version)
}