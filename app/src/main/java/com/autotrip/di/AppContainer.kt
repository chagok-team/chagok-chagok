// 출처: docs/INTERFACES.md v1.1 7장 A와 B의 연결 방식 — 문서의 코드를 그대로 옮김. (A 요청으로 B가 초안 작성, A 소유)
// 구현(AutoTripApplication의 container, Fake·실제 AppContainer)은 A가 v1.2에서 작성한다.
package com.autotrip.di   // A 소유

import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.domain.repository.TripTracker

interface AppContainer {
    val tripTracker: TripTracker
    val tripRepository: TripRepository
    val profileRepository: ProfileRepository
    val reportRepository: ReportRepository
}
