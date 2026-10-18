import { Extension, type Editor, type ExtensionOptions } from '@halo-dev/richtext-editor'
import { markRaw } from 'vue'
import RiSparklingLine from '~icons/ri/sparkling-line'
import CoverToolbarItem from '@/components/CoverToolbarItem.vue'

export const COVER_EXTENSION_NAME = 'ai-cover-extension'

/**
 * 工具栏按钮排序值。
 *
 * <p>Halo 默认编辑器的顶部工具栏由所有扩展返回的 toolbar item 组成，并按 priority
 * 升序排列（Halo 官方机制，见「默认编辑器」扩展点文档），没有按名称或绝对位置排列的 API。
 * 因此这里只提供一个排序值，不读取、不依赖任何其他按钮的 DOM 或名称；即使 Halo 增删其他
 * 按钮，本按钮仍会正常渲染，仅显示顺序可能变化。
 *
 * <p>取 225 使其位于「行高」(220) 与「查找替换」(230) 之间，即工具栏靠右区域。
 */
const COVER_TOOLBAR_PRIORITY = 225

/**
 * 在默认编辑器顶部工具栏注册「AI 生成封面」按钮。
 */
export const coverEditorExtension = Extension.create<ExtensionOptions>({
  name: COVER_EXTENSION_NAME,
  addOptions() {
    return {
      ...this.parent?.(),
      getToolbarItems({ editor }: { editor: Editor }) {
        return {
          priority: COVER_TOOLBAR_PRIORITY,
          component: markRaw(CoverToolbarItem),
          props: {
            editor,
            isActive: false,
            icon: markRaw(RiSparklingLine),
            title: 'AI 生成封面',
          },
        }
      },
    }
  },
})
