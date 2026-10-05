<template>
  <div class="card pixiv-panel">
    <div class="pixiv-panel-head">
      <h2 class="pixiv-panel-title">{{ t('translate.pixiv.panelTitle') }}</h2>
      <button
        type="button"
        class="pixiv-panel-clear"
        :disabled="!hasPanelState"
        :title="t('translate.pixiv.clear')"
        :aria-label="t('translate.pixiv.clear')"
        @click="clearAll"
      >✕</button>
    </div>

    <!-- A. Import by work link -->
    <div class="pixiv-import">
      <input
        v-model="pixivUrl"
        type="url"
        class="pixiv-import-input"
        :placeholder="t('translate.pixiv.placeholder')"
        :disabled="pixivLoading"
        @keyup.enter="importByUrl"
      />
      <button
        type="button"
        class="pixiv-import-btn"
        :disabled="pixivLoading || !pixivUrl.trim()"
        @click="importByUrl"
      >{{ pixivLoading ? t('translate.pixiv.loading') : t('translate.pixiv.action') }}</button>
    </div>
    <p v-if="pixivError" class="pixiv-error">{{ pixivError }}</p>
    <p v-else-if="pixivInfo" class="pixiv-info">{{ pixivInfo }}</p>

    <!-- B. Import from screenshots -->
    <section class="pixiv-shots">
      <div
        class="pixiv-dropzone"
        :class="{ 'pixiv-dropzone--active': dropActive }"
        @dragenter.prevent="onDropzoneDragEnter"
        @dragover.prevent
        @dragleave.prevent="onDropzoneDragLeave"
        @drop.prevent="onDropzoneDrop"
        @click="shotInput?.click()"
      >
        <p class="pixiv-dropzone-hint">{{ t('translate.pixiv.pasteHint') }}</p>
        <p class="pixiv-dropzone-max">{{ t('translate.pixiv.maxImages') }}</p>
      </div>
      <!-- Kept outside the drop zone: a programmatic click() on a nested input would
           bubble back into the zone's click handler and re-open the picker. -->
      <input
        ref="shotInput"
        type="file"
        accept="image/*"
        multiple
        class="pixiv-file-input"
        @change="onPickShotFiles"
      />

      <div v-if="shotPreviews.length" class="pixiv-thumbs">
        <div v-for="(preview, index) in shotPreviews" :key="index" class="pixiv-thumb">
          <img v-if="preview" :src="preview" :alt="String(index + 1)" />
          <span v-else class="pixiv-thumb-placeholder">{{ index + 1 }}</span>
          <button
            type="button"
            class="pixiv-thumb-remove"
            :title="t('translate.pixiv.clear')"
            @click="removeShot(index)"
          >✕</button>
        </div>
      </div>

      <p v-if="imageNotice" class="pixiv-info">{{ imageNotice }}</p>
      <p v-if="recognizeError" class="pixiv-error">{{ recognizeError }}</p>
      <p v-else-if="recognizing" class="pixiv-info">{{ t('translate.pixiv.recognizing') }}</p>

      <template v-if="extracted">
        <div class="pixiv-extracted">
          <div class="pixiv-extracted-head">{{ t('translate.pixiv.extractedInfo') }}</div>
          <dl class="pixiv-meta-list">
            <template v-if="extracted.title">
              <dt>{{ t('translate.pixiv.titleLabel') }}</dt>
              <dd>{{ extracted.title }}</dd>
            </template>
            <template v-if="extracted.author">
              <dt>{{ t('translate.pixiv.authorLabel') }}</dt>
              <dd>{{ extracted.author }}</dd>
            </template>
            <template v-if="extracted.tags.length">
              <dt>{{ t('translate.pixiv.tagsLabel') }}</dt>
              <dd><span v-for="tag in extracted.tags" :key="tag" class="pixiv-tag">{{ tag }}</span></dd>
            </template>
            <template v-if="extracted.summary">
              <dt>{{ t('translate.pixiv.descriptionLabel') }}</dt>
              <dd class="pixiv-meta-description">{{ extracted.summary }}</dd>
            </template>
          </dl>
        </div>

        <div class="pixiv-search">
          <div class="pixiv-search-bar">
            <label class="pixiv-search-label" for="pixiv-search-keyword">{{ t('translate.pixiv.searchKeywordLabel') }}</label>
            <input
              id="pixiv-search-keyword"
              v-model="searchKeyword"
              type="text"
              class="pixiv-search-input"
              @keyup.enter="searchByKeyword"
            />
            <button
              type="button"
              :disabled="searching || !searchKeyword.trim()"
              @click="searchByKeyword"
            >{{ searching ? t('translate.pixiv.searching') : t('translate.pixiv.searchAction') }}</button>
          </div>

          <p v-if="searching" class="pixiv-info">{{ t('translate.pixiv.searching') }}</p>
          <p v-else-if="searchError" class="pixiv-error">{{ searchError }}</p>
          <template v-else>
            <p v-if="searchInfo" class="pixiv-search-info">{{ searchInfo }}</p>
            <ul v-if="candidates.length" class="pixiv-candidates">
            <li v-for="item in candidates" :key="item.id" class="pixiv-candidate">
              <div class="pixiv-candidate-body">
                <div class="pixiv-candidate-title">
                  <span v-if="item.xRestrict > 0" class="pixiv-r18">{{ t('translate.pixiv.r18Badge') }}</span>
                  {{ item.title }}
                </div>
                <div v-if="item.author" class="pixiv-candidate-author">{{ item.author }}</div>
                <div v-if="item.tags.length" class="pixiv-candidate-tags">
                  <span v-for="tag in item.tags.slice(0, 5)" :key="tag" class="pixiv-tag">{{ tag }}</span>
                </div>
                <div class="pixiv-candidate-count">{{ t('translate.pixiv.charCount', { count: item.textCount }) }}</div>
              </div>
              <button
                type="button"
                class="pixiv-candidate-import"
                :disabled="importingId !== ''"
                @click="importCandidate(item)"
              >{{ importingId === item.id ? t('translate.pixiv.loading') : t('translate.pixiv.candidateImport') }}</button>
            </li>
          </ul>
          </template>
        </div>
      </template>

      <button
        v-if="hasShotState"
        type="button"
        class="pixiv-shot-clear"
        @click="clearShots"
      >{{ t('translate.pixiv.shotClear') }}</button>
    </section>

    <!-- C. Imported work metadata -->
    <div v-if="meta" class="pixiv-meta">
      <dl class="pixiv-meta-list">
        <template v-if="meta.title">
          <dt>{{ t('translate.pixiv.titleLabel') }}</dt>
          <dd>{{ meta.title }}</dd>
        </template>
        <template v-if="meta.author">
          <dt>{{ t('translate.pixiv.authorLabel') }}</dt>
          <dd>{{ meta.author }}</dd>
        </template>
        <template v-if="meta.tags.length">
          <dt>{{ t('translate.pixiv.tagsLabel') }}</dt>
          <dd><span v-for="tag in meta.tags" :key="tag" class="pixiv-tag">{{ tag }}</span></dd>
        </template>
        <template v-if="meta.description">
          <dt>{{ t('translate.pixiv.descriptionLabel') }}</dt>
          <dd class="pixiv-meta-description">{{ meta.description }}</dd>
        </template>
      </dl>
      <div class="pixiv-meta-actions">
        <label class="pixiv-meta-check">
          <input type="checkbox" v-model="includeMetadataModel" />
          {{ t('translate.pixiv.includeMetadata') }}
        </label>
        <button
          type="button"
          class="pixiv-meta-translate"
          :disabled="!meta.metadataText || metadataLoading"
          @click="translateMetadata"
        >{{ metadataLoading ? t('translate.pixiv.metadataLoading') : t('translate.pixiv.translateMetadata') }}</button>
      </div>
      <p v-if="metadataError" class="pixiv-error">{{ metadataError }}</p>
      <div v-if="metadataResult" class="pixiv-meta-result">
        <div class="pixiv-meta-result-head">
          <span>{{ t('translate.pixiv.metadataResultTitle') }}</span>
          <button type="button" class="pixiv-meta-result-close" @click="metadataResult = null">✕</button>
        </div>
        <pre class="pixiv-meta-result-text">{{ metadataResult }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import api, { extractPixivNovelInfo, importPixivNovel, searchPixivNovels } from '../api'
import { AUTO_IMPORT_SCORE, concreteTags, isUsableTitle, scoreCandidate, tagSearchKeywords, titleSearchChunk } from '../utils/pixivSearch'
import type {
  PixivExtractResponse,
  PixivNovelResponse,
  PixivSearchItem,
  TranslateResponse
} from '../types'
import { useAuthStore } from '../stores/auth'

const props = defineProps<{
  includeMetadata: boolean
  /**
   * Translation context forwarded from the page so "translate title & summary" runs with the
   * same model / presets / target language the user selected for the main translation —
   * the exact payload the in-card implementation used to send.
   */
  model: string
  /** Null when the user picked a site model rather than a personal profile. */
  modelProfileId: number | null
  customPrompt: string
  presets: string[]
  targetLanguage: string
  /** True while the main translation is running; guards against overlapping /translate calls. */
  busy: boolean
}>()

const emit = defineEmits<{
  (e: 'update:includeMetadata', v: boolean): void
  (e: 'imported', novel: PixivNovelResponse): void
  (e: 'cleared'): void
}>()

const { t } = useI18n()
const authStore = useAuthStore()

/**
 * The metadata toggle lives in the parent (it changes what the parent sends to /translate),
 * so the checkbox writes straight back through the prop/event pair.
 */
const includeMetadataModel = computed({
  get: () => props.includeMetadata,
  set: (value: boolean) => emit('update:includeMetadata', value)
})

// --- A. Link import -------------------------------------------------------------------------

const pixivUrl = ref('')
const pixivLoading = ref(false)
const pixivError = ref('')
const pixivInfo = ref('')
/** Title / author / tags / description of the imported work, shown in section C. */
const meta = ref<PixivNovelResponse | null>(null)
const metadataResult = ref<string | null>(null)
const metadataError = ref('')
const metadataLoading = ref(false)

/** Bumped by the clear button so answers from requests started before it are dropped. */
let linkEpoch = 0
let shotEpoch = 0

/**
 * Imports a Pixiv novel's text by work URL or id, then hands the whole record to the parent.
 * The request goes through the backend, which proxies Pixiv (so no CORS problem in the browser)
 * and strips Pixiv's control tags.
 */
async function importByUrl() {
  const url = pixivUrl.value.trim()
  if (!url || pixivLoading.value) return
  const epoch = linkEpoch
  pixivLoading.value = true
  pixivError.value = ''
  pixivInfo.value = ''
  try {
    const res = await importPixivNovel(url)
    if (epoch !== linkEpoch) return
    applyImportedNovel(res.data)
  } catch (e: unknown) {
    if (epoch !== linkEpoch) return
    const err = e as { response?: { data?: { error?: string } }; message?: string }
    pixivError.value = err.response?.data?.error || t('translate.pixiv.failed')
  } finally {
    if (epoch === linkEpoch) pixivLoading.value = false
  }
}

/**
 * Every successful import (link, or a candidate picked from the screenshot flow) lands here:
 * the parent takes the text into the translate box, while this panel keeps the metadata for
 * display and re-uses the same "imported …" confirmation line.
 */
function applyImportedNovel(novel: PixivNovelResponse) {
  meta.value = novel
  metadataResult.value = null
  metadataError.value = ''
  pixivError.value = ''
  const label = novel.title || t('translate.pixiv.untitled')
  pixivInfo.value = novel.author
    ? t('translate.pixiv.importedWithAuthor', { title: label, author: novel.author })
    : t('translate.pixiv.imported', { title: label })
  emit('imported', novel)
}

/**
 * Translates just the imported work's metadata (title, author, tags, description) and shows it
 * in a small box, so reading the summary does not overwrite the novel translation.
 */
async function translateMetadata() {
  const metadataText = meta.value?.metadataText
  if (!metadataText || metadataLoading.value || props.busy) return
  metadataLoading.value = true
  metadataError.value = ''
  metadataResult.value = null
  try {
    const res = await api.post<TranslateResponse>('/translate', {
      sourceText: metadataText,
      model: props.model,
      modelProfileId: props.modelProfileId ?? undefined,
      customPrompt: props.customPrompt || undefined,
      presets: props.presets.length > 0 ? props.presets : undefined,
      targetLanguage: props.targetLanguage
    })
    metadataResult.value = res.data.translatedText
  } catch (e: unknown) {
    const err = e as { response?: { data?: { error?: string } }; message?: string }
    metadataError.value = err.response?.data?.error || t('translate.pixiv.metadataFailed')
  } finally {
    metadataLoading.value = false
  }
}

// --- B. Screenshot import -------------------------------------------------------------------

/** Mirrors the backend's MAX_EXTRACT_IMAGES; keep the two in sync. */
const MAX_SHOT_IMAGES = 3
/** The candidate list is capped so one search cannot render an endless page. */
const MAX_CANDIDATES = 20

const shotInput = ref<HTMLInputElement | null>(null)
const shotFiles = ref<File[]>([])
const shotPreviews = ref<string[]>([])
const dropActive = ref(false)
const imageNotice = ref('')
const recognizing = ref(false)
const recognizeError = ref('')
const extracted = ref<PixivExtractResponse | null>(null)
const searchKeyword = ref('')
const searching = ref(false)
const searchError = ref('')
const searchPerformed = ref(false)
/** One-line explanation of the last search: which strategy matched, or that all failed. */
const searchInfo = ref('')
const candidates = ref<PixivSearchItem[]>([])
const importingId = ref('')

let dropDepth = 0

function isImageFile(file: File) {
  return file.type.startsWith('image/') || /\.(png|jpe?g|jfif|webp|gif|bmp)$/i.test(file.name)
}

/**
 * Document-level paste handling for screenshots. Only image pastes are considered at all.
 * The translate card owns pasted images while the event lands inside it (there an image
 * becomes an attachment for image translation, which is that block's existing behaviour).
 * Everywhere else — including this panel's own URL/keyword inputs, where a text-field guard
 * used to swallow the paste silently — the panel consumes the image and starts recognition.
 */
function onDocumentPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  const images: File[] = []
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) images.push(file)
    }
  }
  if (!images.length) return
  const el = (e.target instanceof HTMLElement ? e.target : document.activeElement) as HTMLElement | null
  if (el && typeof el.closest === 'function' && el.closest('.translation-card')) return
  e.preventDefault()
  acceptImages(images)
}

onMounted(() => {
  document.addEventListener('paste', onDocumentPaste)
})

onUnmounted(() => {
  document.removeEventListener('paste', onDocumentPaste)
})

function onDropzoneDragEnter() {
  dropDepth++
  dropActive.value = true
}

function onDropzoneDragLeave() {
  dropDepth = Math.max(0, dropDepth - 1)
  if (dropDepth === 0) dropActive.value = false
}

function onDropzoneDrop(e: DragEvent) {
  dropDepth = 0
  dropActive.value = false
  const files = Array.from(e.dataTransfer?.files || [])
  if (files.length) acceptImages(files)
}

function onPickShotFiles(e: Event) {
  const target = e.target as HTMLInputElement
  const files = Array.from(target.files || [])
  if (files.length) acceptImages(files)
  target.value = ''
}

/**
 * Takes at most three images (the backend rejects more) and immediately asks the vision model
 * to read them, so a paste is a one-step "identify this work" action.
 */
function acceptImages(files: File[]) {
  const images = files.filter(isImageFile)
  if (!images.length) return
  imageNotice.value = ''
  const room = MAX_SHOT_IMAGES - shotFiles.value.length
  if (room <= 0) {
    imageNotice.value = t('translate.pixiv.maxImages')
    return
  }
  const accepted = images.slice(0, room)
  if (accepted.length < images.length) imageNotice.value = t('translate.pixiv.maxImages')
  for (const file of accepted) {
    shotFiles.value.push(file)
    shotPreviews.value.push('')
    const reader = new FileReader()
    reader.onload = ev => {
      // Resolve the slot by file identity, so removing a thumbnail while another read is
      // still in flight cannot shift the previews out of order.
      const index = shotFiles.value.indexOf(file)
      if (index >= 0) shotPreviews.value[index] = String(ev.target?.result || '')
    }
    reader.readAsDataURL(file)
  }
  recognize()
}

/**
 * Removing a screenshot invalidates whatever was read from the previous set, so the remaining
 * images are sent again — the panel never claims the shown candidates belong to other images.
 */
function removeShot(index: number) {
  shotFiles.value.splice(index, 1)
  shotPreviews.value.splice(index, 1)
  imageNotice.value = ''
  resetRecognitionResults()
  if (shotFiles.value.length) recognize()
}

function resetRecognitionResults() {
  shotEpoch++
  recognizing.value = false
  recognizeError.value = ''
  extracted.value = null
  searchKeyword.value = ''
  searchPerformed.value = false
  searchError.value = ''
  searchInfo.value = ''
  searching.value = false
  candidates.value = []
  importingId.value = ''
}

/** Empties the screenshot block (images, recognition result, candidates). */
function clearShots() {
  resetRecognitionResults()
  shotFiles.value = []
  shotPreviews.value = []
  imageNotice.value = ''
}

/** Clears every piece of import state this panel owns and tells the parent to drop its copy. */
function clearAll() {
  linkEpoch++
  shotEpoch++
  pixivLoading.value = false
  recognizing.value = false
  searching.value = false
  importingId.value = ''
  pixivUrl.value = ''
  pixivError.value = ''
  pixivInfo.value = ''
  meta.value = null
  metadataResult.value = null
  metadataError.value = ''
  metadataLoading.value = false
  clearShots()
  emit('cleared')
}

/**
 * Sends the current screenshots to the vision model. Sign-in is checked first: without a token
 * the request is guaranteed to answer 401, so the panel shows the sign-in hint instead of a
 * round trip that can only fail.
 */
async function recognize() {
  if (!shotFiles.value.length) return
  recognizeError.value = ''
  if (!authStore.token) {
    resetRecognitionResults()
    recognizeError.value = t('translate.pixiv.loginRequired')
    return
  }
  const epoch = shotEpoch
  recognizing.value = true
  recognizeError.value = ''
  extracted.value = null
  searchPerformed.value = false
  searchError.value = ''
  candidates.value = []
  try {
    const res = await extractPixivNovelInfo(shotFiles.value)
    if (epoch !== shotEpoch) return
    extracted.value = res.data
    await autoSearch(res.data)
  } catch (e: unknown) {
    if (epoch !== shotEpoch) return
    const err = e as { response?: { status?: number; data?: { error?: string } } }
    if (err.response?.status === 401) recognizeError.value = t('translate.pixiv.loginRequired')
    else recognizeError.value = err.response?.data?.error || t('translate.pixiv.recognizeFailed')
  } finally {
    if (epoch === shotEpoch) recognizing.value = false
  }
}

/**
 * Search design: the recognised work is located without any user-selected mode. A mixed
 * "title + tags" keyword matches neither Pixiv mode (that combination was the original bug),
 * so the panel runs several searches in parallel and ranks the merged result — title match
 * first (the strongest signal, per product direction), tag overlap second. Manual searches
 * try tags first and fall back to titles automatically.
 */

/** One search call; returns at most MAX_CANDIDATES items. */
async function runSearch(keyword: string, mode: 'tag' | 'title'): Promise<PixivSearchItem[]> {
  const res = await searchPixivNovels(keyword, mode)
  return (res.data || []).slice(0, MAX_CANDIDATES)
}

/**
 * Auto search after a screenshot was recognised. All strategies fire in parallel — the two
 * strongest tags ANDed, the first tag alone, and the longest title run in title mode — then
 * the results are merged, deduplicated and ranked by scoreCandidate (title match first, tag
 * overlap second). A top candidate with a title match is imported straight away; otherwise
 * the ranked list is shown for one-click confirmation. The user never picks a search mode.
 */
async function autoSearch(info: PixivExtractResponse) {
  const tags = (info.tags || []).map(tag => tag.trim()).filter(Boolean)
  const concrete = concreteTags(tags)
  // Search by title only when the recognised "title" is genuinely a title — the model
  // sometimes promotes a tag (R-18, BLACKSOULSⅡ) to the title field, and searching that
  // would pull in the whole tag's catalogue instead of the work.
  const chunk = isUsableTitle(info.title, tags) ? titleSearchChunk(info.title) : ''
  const searches: Array<Promise<PixivSearchItem[]>> = []
  if (concrete.length >= 2) {
    for (const keyword of tagSearchKeywords(concrete.slice(0, 2))) {
      searches.push(runSearch(keyword, 'tag'))
    }
    // The AND can zero out when one tag rides only on part of the series; also try the
    // most specific single tag.
    for (const keyword of tagSearchKeywords([concrete[0]])) {
      searches.push(runSearch(keyword, 'tag'))
    }
  } else if (concrete.length === 1) {
    for (const keyword of tagSearchKeywords(concrete)) {
      searches.push(runSearch(keyword, 'tag'))
    }
  }
  if (chunk) searches.push(runSearch(chunk, 'title'))
  if (!searches.length) {
    candidates.value = []
    searchPerformed.value = true
    return
  }
  const epoch = shotEpoch
  searching.value = true
  searchError.value = ''
  candidates.value = []
  try {
    const settled = await Promise.allSettled(searches)
    if (epoch !== shotEpoch) return
    const byId = new Map<string, PixivSearchItem>()
    let failures = 0
    for (const result of settled) {
      if (result.status === 'fulfilled') {
        for (const item of result.value) {
          if (!byId.has(item.id)) byId.set(item.id, item)
        }
      } else {
        failures++
      }
    }
    if (failures === settled.length) {
      const reason = (settled[0] as PromiseRejectedResult).reason as
        { response?: { data?: { error?: string } } } | undefined
      searchError.value = reason?.response?.data?.error || t('translate.pixiv.searchFailed')
      return
    }
    const ranked = [...byId.values()]
      .map(item => ({ item, score: scoreCandidate(info, item) }))
      .sort((a, b) => b.score - a.score)
    candidates.value = ranked.map(entry => entry.item).slice(0, MAX_CANDIDATES)
    searchKeyword.value = concrete[0] || chunk
    searchPerformed.value = true
    const top = ranked[0]
    if (top && top.score >= AUTO_IMPORT_SCORE) {
      searchInfo.value = t('translate.pixiv.autoImported', { title: top.item.title })
      await importCandidate(top.item)
    } else if (candidates.value.length) {
      searchInfo.value = t('translate.pixiv.autoCandidates', { count: candidates.value.length })
    } else {
      searchInfo.value = t('translate.pixiv.searchTriedAll', { keyword: searchKeyword.value })
    }
  } catch (e: unknown) {
    if (epoch !== shotEpoch) return
    const err = e as { response?: { data?: { error?: string } } }
    searchError.value = err.response?.data?.error || t('translate.pixiv.searchFailed')
  } finally {
    if (epoch === shotEpoch) searching.value = false
  }
}

/** Manual search from the keyword box: tags first, titles as an automatic fallback. */
async function searchByKeyword() {
  const keyword = searchKeyword.value.trim()
  if (!keyword || searching.value) return
  const epoch = shotEpoch
  searching.value = true
  searchError.value = ''
  candidates.value = []
  try {
    let items = await runSearch(keyword, 'tag')
    if (epoch !== shotEpoch) return
    if (!items.length) {
      items = await runSearch(keyword, 'title')
      if (epoch !== shotEpoch) return
    }
    candidates.value = items
    searchInfo.value = items.length
      ? t('translate.pixiv.searchMatched', { keyword, count: items.length })
      : t('translate.pixiv.searchTriedAll', { keyword })
    searchPerformed.value = true
  } catch (e: unknown) {
    if (epoch !== shotEpoch) return
    const err = e as { response?: { data?: { error?: string } } }
    searchError.value = err.response?.data?.error || t('translate.pixiv.searchFailed')
  } finally {
    if (epoch === shotEpoch) searching.value = false
  }
}

/**
 * Imports the chosen candidate: same path as a link import (text to the parent, metadata kept
 * here), then the screenshot block is emptied so the candidate list folds away.
 */
async function importCandidate(item: PixivSearchItem) {
  if (importingId.value) return
  const epoch = shotEpoch
  importingId.value = item.id
  searchError.value = ''
  try {
    const res = await importPixivNovel(item.id)
    if (epoch !== shotEpoch) return
    applyImportedNovel(res.data)
    clearShots()
  } catch (e: unknown) {
    if (epoch !== shotEpoch) return
    const err = e as { response?: { data?: { error?: string } } }
    searchError.value = err.response?.data?.error || t('translate.pixiv.failed')
  } finally {
    if (epoch === shotEpoch) importingId.value = ''
  }
}

// --- State summaries ------------------------------------------------------------------------

const hasShotState = computed(() =>
  shotFiles.value.length > 0
  || shotPreviews.value.length > 0
  || !!imageNotice.value
  || !!recognizeError.value
  || !!extracted.value
  || candidates.value.length > 0
)

const hasPanelState = computed(() =>
  hasShotState.value
  || !!pixivUrl.value
  || !!pixivInfo.value
  || !!pixivError.value
  || !!meta.value
  || !!metadataResult.value
  || !!metadataError.value
)
</script>

<style scoped>
.pixiv-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.pixiv-panel-title {
  margin: 0;
  font-weight: 600;
  font-size: 18px;
}

.pixiv-panel-clear {
  flex: 0 0 auto;
  padding: 0 8px;
  font-size: 13px;
  background: transparent;
  border: 1px solid transparent;
  color: var(--color-muted, #666);
  cursor: pointer;
}

.pixiv-panel-clear:hover:not(:disabled) {
  border-color: var(--color-border, #dee2e6);
  color: var(--color-text, #222);
}

.pixiv-panel-clear:disabled {
  opacity: 0.35;
  cursor: default;
}

.pixiv-import {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.pixiv-import-input {
  flex: 1;
  min-width: 0;
}

.pixiv-import-btn {
  flex: 0 0 auto;
  white-space: nowrap;
}

.pixiv-error {
  color: #e03131;
  margin: 8px 0 0;
  font-size: 14px;
}

.pixiv-info {
  color: var(--color-muted, #666);
  margin: 8px 0 0;
  font-size: 13px;
}

.pixiv-shots {
  margin-top: 12px;
}

.pixiv-dropzone {
  border: 2px dashed var(--color-border, #dee2e6);
  border-radius: 8px;
  padding: 16px;
  text-align: center;
  cursor: pointer;
  background: var(--color-surface, #fafafa);
  transition: border-color 0.15s, background 0.15s;
}

.pixiv-dropzone:hover,
.pixiv-dropzone--active {
  border-color: #4a9eff;
  background: rgba(74, 158, 255, 0.06);
}

.pixiv-dropzone-hint {
  margin: 0;
  font-size: 14px;
}

.pixiv-dropzone-max {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--color-muted, #666);
}

.pixiv-file-input {
  display: none;
}

.pixiv-thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.pixiv-thumb {
  position: relative;
}

.pixiv-thumb img,
.pixiv-thumb-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  max-height: 90px;
  max-width: 140px;
  min-width: 64px;
  min-height: 64px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--color-border, #dee2e6);
  background: #f1f3f5;
  font-size: 12px;
  color: var(--color-muted, #666);
}

.pixiv-thumb-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  padding: 0;
  border-radius: 50%;
  border: 1px solid var(--color-border, #dee2e6);
  background: #fff;
  color: var(--color-muted, #666);
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
}

.pixiv-thumb-remove:hover {
  color: #e03131;
  border-color: #e03131;
}

.pixiv-extracted {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--color-border, #dee2e6);
  border-radius: 8px;
  background: var(--color-surface, #fafafa);
  font-size: 13px;
}

.pixiv-extracted-head {
  color: var(--color-muted, #666);
  margin-bottom: 6px;
}

.pixiv-meta-list {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 10px;
  margin: 0;
}

.pixiv-meta-list dt {
  color: var(--color-muted, #666);
  white-space: nowrap;
}

.pixiv-meta-list dd {
  margin: 0;
  min-width: 0;
  word-break: break-word;
}

.pixiv-meta-description {
  white-space: pre-wrap;
  max-height: 8em;
  overflow-y: auto;
}

.pixiv-tag {
  display: inline-block;
  margin: 0 6px 4px 0;
  padding: 1px 8px;
  border-radius: 10px;
  background: var(--color-border, #e9ecef);
  font-size: 12px;
}

.pixiv-search {
  margin-top: 12px;
}

.pixiv-search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.pixiv-search-label {
  font-size: 13px;
  color: var(--color-muted, #666);
  white-space: nowrap;
}


.pixiv-search-input {
  flex: 1;
  min-width: 160px;
}

/* Status line: which strategy matched (or that all failed). */
.pixiv-search-info {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--color-muted, #888);
}

.pixiv-candidates {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  max-height: 420px;
  overflow-y: auto;
}

.pixiv-candidate {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 2px;
  border-top: 1px solid var(--color-border, #dee2e6);
}

.pixiv-candidate-body {
  min-width: 0;
  font-size: 13px;
}

.pixiv-candidate-title {
  font-weight: 500;
  word-break: break-word;
}

.pixiv-candidate-author {
  color: var(--color-muted, #666);
  margin-top: 2px;
}

.pixiv-candidate-tags {
  margin-top: 4px;
}

.pixiv-candidate-count {
  color: var(--color-muted, #666);
  margin-top: 4px;
  font-size: 12px;
}

.pixiv-candidate-import {
  flex: 0 0 auto;
  white-space: nowrap;
  align-self: center;
}

.pixiv-r18 {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #e03131;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 16px;
}

.pixiv-shot-clear {
  margin-top: 10px;
  font-size: 13px;
}

.pixiv-meta {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid var(--color-border, #dee2e6);
  border-radius: 8px;
  background: var(--color-surface, #fafafa);
  font-size: 13px;
}

.pixiv-meta-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.pixiv-meta-check {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.pixiv-meta-result {
  margin-top: 10px;
  border-top: 1px solid var(--color-border, #dee2e6);
  padding-top: 8px;
}

.pixiv-meta-result-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--color-muted, #666);
  margin-bottom: 4px;
}

.pixiv-meta-result-close {
  padding: 0 6px;
  font-size: 12px;
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--color-muted, #666);
}

.pixiv-meta-result-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}
</style>
