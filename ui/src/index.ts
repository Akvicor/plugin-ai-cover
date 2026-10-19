import { VDropdownItem } from '@halo-dev/components'
import { definePlugin } from '@halo-dev/ui-shared'
import { markRaw } from 'vue'
import CoverTaskField from './components/task/CoverTaskField.vue'
import { submitBackgroundCover } from './composables/use-cover-tasks'
import { coverEditorExtension } from './tiptap/cover-extension'

export default definePlugin({
  extensionPoints: {
    'default:editor:extension:create': () => [coverEditorExtension],
    // 文章行菜单：没有封面时补充，已有封面时重新生成，均在后台完成并设置封面。
    'post:list-item:operation:create': (post) => {
      const hasCover = !!post.value.post.spec.cover
      return [
        {
          priority: 21,
          component: markRaw(VDropdownItem),
          label: hasCover ? 'AI 生成封面' : 'AI 补充封面',
          // Halo 的超级管理员拥有通配符 UI 权限，与插件 API 的默认访问范围一致。
          permissions: ['*'],
          action: () =>
            submitBackgroundCover(post.value.post.metadata.name, hasCover ? 'REPLACE' : 'FILL'),
        },
      ]
    },
    'post:list-item:field:create': (post) => [
      {
        priority: 60,
        position: 'end',
        component: markRaw(CoverTaskField),
        permissions: ['*'],
        props: {
          postName: post.value.post.metadata.name,
          cover: post.value.post.spec.cover,
        },
      },
    ],
  },
})
