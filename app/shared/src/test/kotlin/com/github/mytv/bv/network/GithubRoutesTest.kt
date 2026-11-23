package com.github.mytv.bv.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程镜像清单的合并与安全校验。
 *
 * 铁律：主机白名单**不是**准入闸门——gitee `my-mirror` 的远程清单要能加新镜像，
 * 六个应用一视同仁。拦住危险主机的是：只接受 https 且 443、不带用户信息/查询/片段、
 * 主机不能是本机或内网。这个测试把这几条钉住。
 */
class GithubRoutesTest {
    @Test
    fun `a remote list can introduce a host outside the built in set`() {
        val routes = GithubRoutes.parseRemote(
            """
            {"schema":"mytv.mirror/v1","mirrors":[
              {"id":"mirror.example.com","label":"mirror.example.com",
               "base":"https://mirror.example.com/","kind":"prefix","api":true,"tier":0}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("mirror.example.com"), routes?.map { it.id })
        assertEquals("https://mirror.example.com/", routes?.first()?.prefix)
    }

    @Test
    fun `a wrong schema is refused`() {
        assertNull(
            GithubRoutes.parseRemote(
                """{"schema":"something.else/v9","mirrors":[]}""",
            ),
        )
    }

    @Test
    fun `local and private hosts are refused`() {
        val routes = GithubRoutes.parseRemote(
            """
            {"schema":"mytv.mirror/v1","mirrors":[
              {"id":"loopback","base":"https://127.0.0.1/","kind":"prefix","tier":0},
              {"id":"lan","base":"https://10.0.0.8/","kind":"prefix","tier":0},
              {"id":"localhost-name","base":"https://mirror.localhost/","kind":"prefix","tier":0},
              {"id":"shorthand","base":"https://127.1/","kind":"prefix","tier":0},
              {"id":"decimal","base":"https://2130706433/","kind":"prefix","tier":0},
              {"id":"plain-http","base":"http://mirror.example.com/","kind":"prefix","tier":0},
              {"id":"with-creds","base":"https://u:p@mirror.example.com/","kind":"prefix","tier":0},
              {"id":"with-query","base":"https://mirror.example.com/?a=1","kind":"prefix","tier":0}
            ]}
            """.trimIndent(),
        )

        assertTrue(routes.isNullOrEmpty())
    }

    @Test
    fun `a raw host entry keeps only the host name`() {
        val routes = GithubRoutes.parseRemote(
            """
            {"schema":"mytv.mirror/v1","mirrors":[
              {"id":"raw.example.com","base":"raw.example.com","kind":"raw_host","tier":3}
            ]}
            """.trimIndent(),
        )

        assertEquals("raw.example.com", routes?.first()?.prefix)
        assertEquals(MirrorKind.RAW_HOST, routes?.first()?.kind)
        // 只服务 raw：遇到 Release 资产地址时不该生成候选。
        assertNull(routes?.first()?.urlFor("https://github.com/o/r/releases/download/v1/a.apk"))
        assertEquals(
            "https://raw.example.com/o/r/main/f.txt",
            routes?.first()?.urlFor("https://raw.githubusercontent.com/o/r/main/f.txt"),
        )
    }

    @Test
    fun `a disabled entry is kept as a route but marked disabled`() {
        val routes = GithubRoutes.parseRemote(
            """
            {"schema":"mytv.mirror/v1","mirrors":[
              {"id":"mirror.example.com","base":"https://mirror.example.com/","kind":"prefix","tier":0,"enabled":false}
            ]}
            """.trimIndent(),
        )

        assertEquals(false, routes?.first()?.enabled)
    }
}
