package dev.aaa1115910.biliapi.http.util

object BiliCustomParams {
    /** 文档 §3.1 的 HD 统计参数：NEURON_APPID=5，platformId=3。 */
    fun statistics(): String =
        """{"appId":${BiliAppConf.APP_ID},"platform":3,"version":"${BiliAppConf.APP_VERSION_NAME}","abtest":""}"""

    fun defaults(): Map<String, String> = mapOf("statistics" to statistics())
}
