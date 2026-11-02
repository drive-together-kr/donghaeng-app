package app.donghaeng.car.feature.settings

import app.donghaeng.car.CardTapPolicy
import app.donghaeng.car.MainActivity
import app.donghaeng.car.domain.model.CapabilityDomain
import app.donghaeng.car.domain.model.CapabilityEntry
import app.donghaeng.car.domain.model.CapabilitySource
import app.donghaeng.car.domain.model.VehicleCapabilities
import app.donghaeng.car.feature.drive.DemoTour
import app.donghaeng.car.ui.navigation.DhRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 규칙들 — 설정 스위치 주행 중 잠금(3-5·E035), 카드 탭(B125), 차량 기능 요약(5-2), 예시 화면 순서(2-1), 권한 요청을 미루는 화면 */
class ScreenPoliciesTest {

    @Test
    fun `settings switches lock while driving, voice can still be turned off`() {
        assertTrue(SettingsLocks.lockedWhileDriving(isDriving = false))
        assertFalse(SettingsLocks.lockedWhileDriving(isDriving = true))
        assertTrue("정차 중엔 켜기·끄기", SettingsLocks.voiceSwitchEnabled(isDriving = false, voiceOn = false))
        assertTrue("주행 중 켜져 있으면 끌 수 있다", SettingsLocks.voiceSwitchEnabled(isDriving = true, voiceOn = true))
        assertFalse("주행 중 꺼져 있으면 다시 켜지 못한다", SettingsLocks.voiceSwitchEnabled(isDriving = true, voiceOn = false))
    }

    @Test
    fun `a card tapped while driving does not open the app screen`() {
        assertFalse(CardTapPolicy.opensScreen(MainActivity.FROM_CARD, drivingNow = true))
        assertTrue(CardTapPolicy.opensScreen(MainActivity.FROM_CARD, drivingNow = false))
        assertTrue("카드가 아닌 인텐트(시연 스크립트·상시 알림)는 그대로", CardTapPolicy.opensScreen(null, drivingNow = true))
    }

    @Test
    fun `vehicle feature summary lists used, hidden and unknown signals in plain words without car info`() {
        val caps = VehicleCapabilities(
            entries = listOf(
                CapabilityEntry(CapabilityDomain.CAR_INFO, true, "", CapabilitySource.PLEOS_SDK),
                CapabilityEntry(CapabilityDomain.SPEED, true, "", CapabilitySource.PLEOS_SDK),
                CapabilityEntry(CapabilityDomain.EV_BATTERY, true, "", CapabilitySource.PLEOS_SDK),
                CapabilityEntry(CapabilityDomain.FUEL, false, "", CapabilitySource.PLEOS_SDK),
                CapabilityEntry(CapabilityDomain.AIRBAG, false, "", CapabilitySource.PLEOS_SDK),
                CapabilityEntry(CapabilityDomain.GEAR, null, "", CapabilitySource.UNCHECKED),
            ),
        )
        val s = VehicleFeatureSummary.of(caps)!!
        assertEquals(listOf("속도", "EV 배터리"), s.used)
        assertEquals(listOf("연료", "에어백"), s.hidden)
        assertEquals(listOf("기어"), s.unknown)
        assertNull(VehicleFeatureSummary.of(null))
    }

    @Test
    fun `demo tour walks the five sample screens and wraps around`() {
        assertEquals(listOf("parked", "panel_light", "panel_danger", "ptt_confirm", "ptt_playing"), DemoTour.STEPS.map { it.variant })
        assertEquals(1, DemoTour.next(0))
        assertEquals(0, DemoTour.next(DemoTour.STEPS.lastIndex))
    }

    @Test
    fun `create form explains a disabled start button when no destination is picked`() {
        val form = app.donghaeng.car.feature.creategroup.CreateGroupState.Form(name = "아빠 차와 같이 가기", destination = null, fromNavi = false, maxMembers = 10)
        assertFalse(form.canCreate)
        assertTrue(form.needsDestination)
        assertFalse("내비 목적지를 읽는 중이면 아직 모른다", form.copy(loadingNavi = true).needsDestination)
        assertFalse("페어링이 없으면 다른 안내", form.copy(notPaired = true).needsDestination)
        val picked = form.copy(destination = app.donghaeng.protocol.Place(37.7, 128.9, "경포해변"))
        assertFalse(picked.needsDestination)
        assertTrue(picked.canCreate)
    }

    @Test
    fun `permission ask is deferred on the invite qr and sample screens`() {
        assertTrue(DhRoutes.defersPermissionAsk(DhRoutes.CREATE_GROUP))
        assertTrue(DhRoutes.defersPermissionAsk(DhRoutes.TOUR))
        assertTrue(DhRoutes.defersPermissionAsk("${DhRoutes.DESIGN}/{variant}"))
        assertFalse(DhRoutes.defersPermissionAsk(DhRoutes.GROUP_DRIVE))
        assertFalse(DhRoutes.defersPermissionAsk(null))
    }
}
