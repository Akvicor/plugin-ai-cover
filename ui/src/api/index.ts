import { axiosInstance } from '@halo-dev/api-client'
import {
  ConsoleApiAicoverHaloRunV1alpha1CoverApi,
  type ConsoleApiAicoverHaloRunV1alpha1CoverApiUploadCoverRequest,
  type Attachment,
  type BackgroundCoverRequestModeEnum,
  type CoverConfigResponse,
  type CoverTaskListResponse,
  type CoverTaskResponse,
  type GenerateCoverRequest,
} from './generated'

const coverApiClient = {
  cover: new ConsoleApiAicoverHaloRunV1alpha1CoverApi(undefined, '', axiosInstance),
}

/**
 * 插件自定义端点封装。
 *
 * 类型与请求方法由 `generateApiClient` 依据后端 `CoverConsoleEndpoint` 的 OpenAPI
 * 描述生成，统一复用 Halo 的 axiosInstance，错误提示由 Halo 的全局拦截器展示。
 *
 * 文章标题与正文由后端根据 postName 读取。图片生成耗时较长，后端采用「提交任务 + 查询状态」
 * 两段式，避免长连接被反向代理中断；同一文章正在生成时提交请求返回 409。
 */
export const coverApi = {
  async getConfig(postName?: string): Promise<CoverConfigResponse> {
    const { data } = await coverApiClient.cover.getCoverConfig({ postName })
    return data
  },

  /** 编辑器提交生成任务，立即返回任务摘要。 */
  async generate(request: GenerateCoverRequest): Promise<CoverTaskResponse> {
    const { data } = await coverApiClient.cover.generateCover({
      generateCoverRequest: request,
    })
    return data
  },

  /** 读取编辑器任务详情，包含仍保留在服务端的图片。 */
  async getGenerateTask(taskId: string): Promise<CoverTaskResponse> {
    const { data } = await coverApiClient.cover.getGenerateCoverTask({ taskId })
    return data
  },

  /** 一次读取各文章最新任务的摘要，不含图片数据。 */
  async listTasks(): Promise<CoverTaskListResponse> {
    const { data } = await coverApiClient.cover.listCoverTasks()
    return data
  },

  /** 文章列表提交后台任务：补充封面或重新生成。 */
  async submitBackground(
    postName: string,
    mode: BackgroundCoverRequestModeEnum,
  ): Promise<CoverTaskResponse> {
    const { data } = await coverApiClient.cover.submitBackgroundCover({
      backgroundCoverRequest: { postName, mode },
    })
    return data
  },

  /** 以 multipart 文件上传，携带编辑器任务 ID 时服务端随后释放图片。 */
  async upload(
    request: ConsoleApiAicoverHaloRunV1alpha1CoverApiUploadCoverRequest,
  ): Promise<Attachment> {
    const { data } = await coverApiClient.cover.uploadCover(request)
    return data
  },
}

export type {
  Attachment,
  BackgroundCoverRequestModeEnum,
  CoverConfigResponse,
  CoverTaskListResponse,
  CoverTaskResponse,
  GenerateCoverRequest,
}
