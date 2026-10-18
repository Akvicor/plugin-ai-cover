import { definePlugin } from '@halo-dev/ui-shared'
import { coverEditorExtension } from './tiptap/cover-extension'

export default definePlugin({
  extensionPoints: {
    'default:editor:extension:create': () => [coverEditorExtension],
  },
})
