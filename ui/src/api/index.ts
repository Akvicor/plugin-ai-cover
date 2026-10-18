import { axiosInstance } from '@halo-dev/api-client'
import {
  ConsoleApiAicoverHaloRunV1alpha1CoverApi,
  type ConsoleApiAicoverHaloRunV1alpha1CoverApiUploadCoverRequest,
  type Attachment,
  type CoverConfigResponse,
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
 * 描述生成，统一复用 Halo 的 axiosInstance。
 *
 * 文章标题与正文由后端根据 postName 读取，前端传入文章标识、提示词及可选标题覆盖值。
 * 图片生成耗时较长，后端采用「提交任务 + 轮询结果」两段式，避免长连接被反向代理中断。
 */
export const coverApi = {
  async getConfig(postName?: string): Promise<CoverConfigResponse> {
    const { data } = await coverApiClient.cover.getCoverConfig({ postName })
    return data
  },

  /** 提交生成任务，立即返回任务 ID。 */
  async generate(request: GenerateCoverRequest): Promise<CoverTaskResponse> {
    const { data } = await coverApiClient.cover.generateCover({
      generateCoverRequest: request,
    })
    return data
  },

  /** 查询生成任务状态。 */
  async getGenerateTask(taskId: string): Promise<CoverTaskResponse> {
    const { data } = await coverApiClient.cover.getGenerateCoverTask({ taskId })
    return data
  },

  /** 以 multipart 文件上传，字段类型由后端 OpenAPI 生成。 */
  async upload(
    request: ConsoleApiAicoverHaloRunV1alpha1CoverApiUploadCoverRequest,
  ): Promise<Attachment> {
    const { data } = await coverApiClient.cover.uploadCover(request)
    return data
  },
}

export type { Attachment, CoverConfigResponse, CoverTaskResponse, GenerateCoverRequest }
