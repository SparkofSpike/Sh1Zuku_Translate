import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * The translate request that the focus page (`/long-novel`) is about to run.
 *
 * The translate page assembles it, hands it over with `start()`, and navigates; the focus
 * page takes it back once with `take()` on mount. A Pinia store (not the URL or storage) is
 * deliberate: the source text can be hundreds of thousands of characters, far beyond what
 * sessionStorage keeps reliably, and the job is a one-shot handoff between two pages of the
 * same SPA session.
 */
export interface LongNovelJob {
  sourceText: string
  model?: string
  modelProfileId?: number | null
  customPrompt?: string
  presets?: string[]
  targetLanguage?: string
  /** Re-translate: bypass every existing result and force a fresh model call. */
  skipCache?: boolean
  /** requestId of the result being re-translated, for the feedback pipeline. */
  retranslatedFrom?: string
  /** DeepSeek thinking mode for this run. */
  thinkingType?: 'enabled' | 'disabled'
}

export const useLongNovelStore = defineStore('longNovel', () => {
  const job = ref<LongNovelJob | null>(null)

  function start(pending: LongNovelJob) {
    job.value = pending
  }

  /** Reads the pending job once and clears it, so revisiting the page does not restart it. */
  function take(): LongNovelJob | null {
    const pending = job.value
    job.value = null
    return pending
  }

  return { job, start, take }
})
