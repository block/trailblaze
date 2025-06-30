package xyz.block.trailblaze.agent.model

// TODO: How else can instructions be provided?
// Mostly thinking how to log the test case from the open ai runner
abstract class TestCase(val instructions: String) {
  abstract val objectives: List<TestObjective>
}
