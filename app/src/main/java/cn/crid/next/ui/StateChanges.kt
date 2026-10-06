package cn.crid.next.ui

import cn.crid.next.core.*

/** Apply one UI action to current data without replacing unrelated concurrent writes. */
internal fun applyStateChanges(base: AppState, next: AppState, current: AppState): AppState {
    val removedTerms=base.semesters.map {it.id}.toSet()-next.semesters.map {it.id}.toSet()
    val changedTerms=next.semesters.filter {term->base.semesters.find {it.id==term.id}!=term}.associateBy {it.id}
    val semesters=current.semesters.filterNot {it.id in removedTerms}.map {changedTerms[it.id]?:it}+
        changedTerms.values.filter {term->current.semesters.none {it.id==term.id}&&base.semesters.none {it.id==term.id}}
    val removedPlans=base.plans.map {it.id}.toSet()-next.plans.map {it.id}.toSet()
    val changedPlans=next.plans.filter {p->base.plans.find {it.id==p.id}!=p}.associateBy {it.id}
    val plans=(current.plans.filterNot {it.id in removedPlans}.map {live->
        val updated=changedPlans[live.id]
        val before=base.plans.find {it.id==live.id}
        if(updated==null||before==null)live else live.copy(
            name=if(updated.name!=before.name)updated.name else live.name,
            courses=if(updated.courses!=before.courses)updated.courses else live.courses,
            semesterId=if(updated.semesterId!=before.semesterId)updated.semesterId else live.semesterId
        )
    }+changedPlans.values.filter {p->current.plans.none {it.id==p.id}&&base.plans.none {it.id==p.id}})
        .filter {p->semesters.any {it.id==p.semesterId}}
    val old=base.settings;val changed=next.settings;val live=current.settings
    val settings=live.copy(
        theme=if(changed.theme!=old.theme)changed.theme else live.theme,
        language=if(changed.language!=old.language)changed.language else live.language,
        weekStartsSunday=if(changed.weekStartsSunday!=old.weekStartsSunday)changed.weekStartsSunday else live.weekStartsSunday,
        holidaysEnabled=if(changed.holidaysEnabled!=old.holidaysEnabled)changed.holidaysEnabled else live.holidaysEnabled,
        makeupMode=if(changed.makeupMode!=old.makeupMode)changed.makeupMode else live.makeupMode,
        showOutOfWeek=if(changed.showOutOfWeek!=old.showOutOfWeek)changed.showOutOfWeek else live.showOutOfWeek,
        remindersEnabled=if(changed.remindersEnabled!=old.remindersEnabled)changed.remindersEnabled else live.remindersEnabled,
        reminderMinutes=if(changed.reminderMinutes!=old.reminderMinutes)changed.reminderMinutes else live.reminderMinutes,
        reminderAlarmClock=if(changed.reminderAlarmClock!=old.reminderAlarmClock)changed.reminderAlarmClock else live.reminderAlarmClock
    )
    val selectedSemester=(if(next.selectedSemesterId!=base.selectedSemesterId)next.selectedSemesterId else current.selectedSemesterId)
        .takeIf {id->semesters.any {it.id==id}} ?: semesters.firstOrNull()?.id
    val selectedPlan=(if(next.selectedPlanId!=base.selectedPlanId)next.selectedPlanId else current.selectedPlanId)
        .takeIf {id->plans.any {it.id==id&&it.semesterId==selectedSemester}} ?: plans.firstOrNull {it.semesterId==selectedSemester}?.id
    return current.copy(semesters=semesters,plans=plans,selectedSemesterId=selectedSemester,selectedPlanId=selectedPlan,settings=settings)
}
