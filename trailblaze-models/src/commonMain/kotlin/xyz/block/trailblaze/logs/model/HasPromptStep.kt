package xyz.block.trailblaze.logs.model

import xyz.block.trailblaze.yaml.PromptStep

interface HasPromptStep {
  val promptStep: PromptStep
}
