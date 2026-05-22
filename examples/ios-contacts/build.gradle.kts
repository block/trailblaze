// The iOS Contacts example is CLI-first — there's no JUnit harness here. The
// only thing this Gradle module does is run the `trailblaze.bundle` plugin to
// generate per-pack TypeScript bindings (`tools/.trailblaze/tools.d.ts`) so
// IDE autocomplete works while authoring `contacts_ios_*` tools.
//
// Trails under `trails/ios-contacts/` are exercised by `./trailblaze trail …`
// directly — both for local development and CI. This module deliberately has
// no test sources, no test dependencies, and no `tasks.test` config.
plugins {
  id("trailblaze.bundle")
}

trailblazeBundle {
  packsDir.set(layout.projectDirectory.dir("trails/config/packs"))
}
