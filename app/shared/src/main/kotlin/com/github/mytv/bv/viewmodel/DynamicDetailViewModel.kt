package com.github.mytv.bv.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.repositories.UserRepository
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.util.fException
import com.github.mytv.bv.util.fInfo
import com.github.mytv.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class DynamicDetailViewModel(
    private val userRepository: UserRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    var dynamicId by mutableStateOf("")
    var dynamicItem by mutableStateOf<DynamicItem?>(null)

    suspend fun loadDynamic() {
        logger.fInfo { "Loading dynamic detail: $dynamicId" }
        // 重试时先清掉旧数据，避免失败后仍显示上一条动态
        dynamicItem = null
        runCatching {
            dynamicItem = userRepository.getDynamicDetail(
                dynamicId = dynamicId,
                preferApiType = Prefs.apiType
            )
        }.onFailure {
            logger.fException(it) { "Failed to load dynamic" }
            withContext(Dispatchers.Main) {
                when (it) {
                    is dev.aaa1115910.biliapi.http.entity.RiskControlException ->
                        "动态被风控拦截，请稍后重试".toast(BVApp.context)

                    else -> "加载动态失败: ${it.localizedMessage}".toast(BVApp.context)
                }
            }
        }
    }
}