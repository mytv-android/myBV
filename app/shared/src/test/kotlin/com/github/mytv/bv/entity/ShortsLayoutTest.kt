package com.github.mytv.bv.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设计稿「大屏与折叠」里的七种形态。容器尺寸已扣掉 80dp 导航轨（有导航轨的形态），
 * 竖向折痕的位置同样减去导航轨宽度。
 */
class ShortsLayoutTest {

    private val portrait = 1080 to 1920
    private val landscape = 1920 to 1080

    private fun layout(
        w: Float,
        h: Float,
        video: Pair<Int, Int>,
        fold: ShortsFold? = null,
        comments: Boolean = false,
        bottomInset: Float = 0f,
    ) = resolveShortsLayout(w, h, fold, video.first, video.second, comments, bottomInset)

    private fun assertRect(expected: ShortsRect, actual: ShortsRect) {
        assertEquals(expected.x, actual.x, 0.5f, "x")
        assertEquals(expected.y, actual.y, 0.5f, "y")
        assertEquals(expected.width, actual.width, 0.5f, "width")
        assertEquals(expected.height, actual.height, 0.5f, "height")
    }

    @Test
    fun `tablet landscape portrait video sticks left with two columns`() {
        val l = layout(1200f, 800f, portrait)
        assertFalse(l.cover)
        assertRect(ShortsRect(0f, 0f, 450f, 800f), l.video)
        val zone = assertNotNull(l.zone(ShortsZoneKind.InfoAndComments))
        assertRect(ShortsRect(450f, 0f, 750f, 800f), zone.rect)
    }

    @Test
    fun `tablet landscape landscape video sticks top with info strip`() {
        val l = layout(1200f, 800f, landscape)
        assertFalse(l.cover)
        assertEquals(0.156f, l.crop, 0.01f)
        assertRect(ShortsRect(0f, 0f, 1200f, 675f), l.video)
        val strip = assertNotNull(l.zone(ShortsZoneKind.InfoStrip))
        assertEquals(125f, strip.rect.height, 0.5f)
    }

    @Test
    fun `tablet landscape landscape video yields 400 to comments`() {
        val l = layout(1200f, 800f, landscape, comments = true)
        assertRect(ShortsRect(0f, 0f, 800f, 450f), l.video)
        val comments = assertNotNull(l.zone(ShortsZoneKind.Comments))
        assertRect(ShortsRect(800f, 0f, 400f, 800f), comments.rect)
        // 评论已经在右侧，下面只放信息条
        assertNotNull(l.zone(ShortsZoneKind.InfoStrip))
        assertNull(l.zone(ShortsZoneKind.InfoStripWithComments))
    }

    @Test
    fun `comments do not move portrait video`() {
        val closed = layout(1200f, 800f, portrait)
        val open = layout(1200f, 800f, portrait, comments = true)
        assertEquals(closed, open)
    }

    @Test
    fun `tablet portrait covers with ten percent crop`() {
        val l = layout(800f, 1280f, portrait)
        assertTrue(l.cover)
        assertEquals(0.1f, l.crop, 0.005f)
        assertRect(ShortsRect(0f, 0f, 800f, 1280f), l.video)
        assertTrue(l.zones.isEmpty())
    }

    @Test
    fun `unfolded flat puts info pane on the right`() {
        val l = layout(750f, 690f, portrait)
        assertFalse(l.cover)
        assertEquals(388f, l.video.width, 0.5f)
        val zone = assertNotNull(l.zone(ShortsZoneKind.InfoTabs))
        assertEquals(362f, zone.rect.width, 0.5f)
    }

    @Test
    fun `unfolded flat 4 by 3 video covers instead of a squeezed strip`() {
        // Pixel Fold 展开扣掉导航轨约 745×640：4:3 宽度顶满后下方只剩 81，两行信息条放不下
        val l = layout(745f, 640f, 1440 to 1080)
        assertTrue(l.cover)
        assertEquals(0.127f, l.crop, 0.005f)
        assertRect(ShortsRect(0f, 0f, 745f, 640f), l.video)
        assertTrue(l.zones.isEmpty())
    }

    @Test
    fun `unfolded flat with taskbar covers above the taskbar`() {
        // 实机：Pixel Fold 展开，容器 745×701，底部任务栏 60 不透明。宽度顶满后下方 142 里只有 82 露在任务栏上面；
        // 铺满整个容器要裁 20.3%（超过上限），只铺到任务栏上沿裁 12.8%
        val l = layout(745f, 701f, 1440 to 1080, bottomInset = 60f)
        assertTrue(l.cover)
        assertEquals(0.128f, l.crop, 0.005f)
        assertRect(ShortsRect(0f, 0f, 745f, 641f), l.video)
        assertTrue(l.zones.isEmpty())
    }

    @Test
    fun `bottom inset does not squeeze a strip that still fits`() {
        // 平板横屏 1200×800 + 24 手势条：信息条 125，露出 101，一行信息条（84）放得下
        val l = layout(1200f, 800f, landscape, bottomInset = 24f)
        assertFalse(l.cover)
        val strip = assertNotNull(l.zone(ShortsZoneKind.InfoStrip))
        assertEquals(125f, strip.rect.height, 0.5f)
    }

    @Test
    fun `strip with comments needs its height above the bottom inset`() {
        // 竖屏平板 800×1280 看横屏：画面 450，下方 830；任务栏 500 时只露出 330，不够再接评论
        assertNotNull(layout(800f, 1280f, landscape).zone(ShortsZoneKind.InfoStripWithComments))
        assertNotNull(layout(800f, 1280f, landscape, bottomInset = 500f).zone(ShortsZoneKind.InfoStrip))
    }

    @Test
    fun `wide strip needs less height than a two row strip`() {
        assertEquals(ShortsLayoutRules.STRIP_MIN_HEIGHT_WIDE, shortsStripMinHeight(1200f))
        assertEquals(ShortsLayoutRules.STRIP_MIN_HEIGHT, shortsStripMinHeight(745f))
    }

    @Test
    fun `squeezed strip stays when covering would crop too much`() {
        // 下方只剩 110（两行信息条要 120），但铺满要裁 22%，超过放宽后的上限，仍然完整显示
        val l = layout(693f, 500f, landscape)
        assertFalse(l.cover)
        assertEquals(0.22f, l.crop, 0.005f)
        val strip = assertNotNull(l.zone(ShortsZoneKind.InfoStrip))
        assertEquals(110f, strip.rect.height, 0.5f)
    }

    @Test
    fun `half opened book covers first pane and puts comments on the second`() {
        val l = layout(750f, 690f, portrait, ShortsFold(vertical = true, start = 335f, thickness = 0f, occluding = false))
        assertTrue(l.split)
        assertTrue(l.cover)
        assertEquals(0.137f, l.crop, 0.005f)
        assertRect(ShortsRect(0f, 0f, 335f, 690f), l.video)
        val comments = assertNotNull(l.zone(ShortsZoneKind.Comments))
        assertRect(ShortsRect(335f, 0f, 415f, 690f), comments.rect)
        assertNull(l.hinge)
    }

    @Test
    fun `half opened tabletop puts controls below the crease`() {
        val fold = ShortsFold(vertical = false, start = 415f, thickness = 0f, occluding = false)
        val p = layout(690f, 830f, portrait, fold)
        assertFalse(p.cover)
        assertRect(ShortsRect(0f, 0f, 233f, 415f), p.video)
        // 评论不在第一片里（控制区那边有入口）
        assertNotNull(p.zone(ShortsZoneKind.Info))
        val controls = assertNotNull(p.zone(ShortsZoneKind.Controls))
        assertRect(ShortsRect(0f, 415f, 690f, 415f), controls.rect)

        val l = layout(690f, 830f, landscape, fold)
        assertTrue(l.cover)
        assertEquals(0.065f, l.crop, 0.005f)
        assertRect(ShortsRect(0f, 0f, 690f, 415f), l.video)
    }

    @Test
    fun `dual screen spanned keeps the hinge empty`() {
        val l = layout(1022f, 757f, portrait, ShortsFold(vertical = true, start = 458f, thickness = 26f, occluding = true))
        assertTrue(l.cover)
        assertEquals(0.07f, l.crop, 0.005f)
        assertRect(ShortsRect(0f, 0f, 458f, 757f), l.video)
        val hinge = assertNotNull(l.hinge)
        assertRect(ShortsRect(458f, 0f, 26f, 757f), hinge)
        val comments = assertNotNull(l.zone(ShortsZoneKind.Comments))
        assertRect(ShortsRect(484f, 0f, 538f, 757f), comments.rect)
    }

    @Test
    fun `dual screen tall puts video on top screen and controls below`() {
        val l = layout(757f, 1102f, portrait, ShortsFold(vertical = false, start = 538f, thickness = 26f, occluding = true))
        assertFalse(l.cover)
        assertEquals(303f, l.video.width, 0.5f)
        assertEquals(538f, l.video.height, 0.5f)
        val info = assertNotNull(l.zone(ShortsZoneKind.Info))
        assertEquals(454f, info.rect.width, 0.5f)
        val controls = assertNotNull(l.zone(ShortsZoneKind.Controls))
        assertRect(ShortsRect(0f, 564f, 757f, 538f), controls.rect)
    }

    @Test
    fun `fold outside the container is ignored`() {
        val l = layout(1200f, 800f, portrait, ShortsFold(vertical = true, start = 1300f, thickness = 0f, occluding = false))
        assertFalse(l.split)
        assertNotNull(l.zone(ShortsZoneKind.InfoAndComments))
    }

    @Test
    fun `narrow spare becomes the action column`() {
        // 700×800：竖屏画面 450 宽，右侧只剩 250
        val l = layout(700f, 800f, portrait)
        assertNotNull(l.zone(ShortsZoneKind.Actions))
    }

    @Test
    fun `unknown size is treated as landscape`() {
        val l = resolveShortsLayout(1200f, 800f, null, 0, 0, false)
        assertEquals(675f, l.video.height, 0.5f)
    }
}
