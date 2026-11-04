package com.twoshare.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.CalendarEvent
import com.twoshare.app.data.CalendarRepository
import com.twoshare.app.data.TwoShareRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val phZone=ZoneId.of("Asia/Manila")
private val dateFmt=DateTimeFormatter.ofPattern("dd MMM yyyy")
private val timeFmt=DateTimeFormatter.ofPattern("h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CalendarScreen(repository:TwoShareRepository,onBack:()->Unit){
 val scope=rememberCoroutineScope();val repo=remember(repository){CalendarRepository(repository)}
 var month by remember{mutableStateOf(YearMonth.now(phZone))};var selected by remember{mutableStateOf(LocalDate.now(phZone))};var events by remember{mutableStateOf(emptyList<CalendarEvent>())};var error by remember{mutableStateOf<String?>(null)};var editing by remember{mutableStateOf<CalendarEvent?>(null)};var creating by remember{mutableStateOf(false)};var agenda by remember{mutableStateOf(false)}
 suspend fun refresh(){runCatchingActive{repo.loadEvents()}.onSuccess{events=it;error=null}.onFailure{error=it.localizedMessage?:"Unable to load calendar."}}
 LaunchedEffect(Unit){refresh();while(isActive){delay(15000);refresh()}}
 val dayEvents=remember(events,selected){events.filter{occursOn(it,selected)}.sortedBy{it.startAt}}
 val upcoming=remember(events){val today=LocalDate.now(phZone);events.filter{eventDate(it)>=today}.sortedBy{it.startAt}.take(40)}
 Scaffold(topBar={TopAppBar(title={Column{Text("Shared calendar",fontWeight=FontWeight.Bold);Text(if(agenda)"Upcoming schedule" else month.month.getDisplayName(TextStyle.FULL,Locale.getDefault())+" "+month.year,style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},actions={TextButton(onClick={agenda=!agenda}){Text(if(agenda)"Month" else "Agenda")}})},floatingActionButton={FloatingActionButton(onClick={creating=true;editing=null}){Icon(Icons.Default.Add,"Add schedule")}}){padding->
  Column(Modifier.fillMaxSize().padding(padding)){error?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)};if(agenda)AgendaList(upcoming){editing=it}else{Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick={month=month.minusMonths(1);selected=month.atDay(1)}){Icon(Icons.Default.ChevronLeft,"Previous month")};Spacer(Modifier.weight(1f));Text(month.month.getDisplayName(TextStyle.FULL,Locale.getDefault())+" "+month.year,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.weight(1f));IconButton(onClick={month=month.plusMonths(1);selected=month.atDay(1)}){Icon(Icons.Default.ChevronRight,"Next month")}};MonthGrid(month,selected,events){selected=it};HorizontalDivider();Text(selected.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM")),Modifier.padding(14.dp),fontWeight=FontWeight.SemiBold);if(dayEvents.isEmpty())Box(Modifier.fillMaxWidth().padding(28.dp),contentAlignment=Alignment.Center){Text("No schedule for this day",color=MaterialTheme.colorScheme.onSurfaceVariant)}else LazyColumn(contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=96.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(dayEvents,key={it.id}){EventCard(it){editing=it}}}}}
 }
 if(creating||editing!=null)EventEditor(editing,selected,{creating=false;editing=null},{title,notes,category,start,end,allDay,repeat,reminder->scope.launch{runCatchingActive{if(editing==null)repo.createEvent(title,notes,category,start,end,allDay,repeat,reminder)else repo.updateEvent(editing!!,title,notes,category,start,end,allDay,repeat,reminder)}.onSuccess{creating=false;editing=null;refresh()}.onFailure{error=it.localizedMessage?:"Unable to save schedule."}}},{event->scope.launch{runCatchingActive{repo.deleteEvent(event)}.onSuccess{editing=null;refresh()}.onFailure{error="Unable to delete schedule."}}})
}

@Composable private fun MonthGrid(month:YearMonth,selected:LocalDate,events:List<CalendarEvent>,pick:(LocalDate)->Unit){
 val first=month.atDay(1);val today=LocalDate.now(phZone)
 val sundayFirstOffset=first.dayOfWeek.value%7
 val cells=List<LocalDate?>(sundayFirstOffset){null}+(1..month.lengthOfMonth()).map{month.atDay(it)}
 Column(Modifier.fillMaxWidth().padding(horizontal=8.dp)){
  Row(Modifier.fillMaxWidth()){listOf("S","M","T","W","T","F","S").forEach{day->Box(Modifier.weight(1f).aspectRatio(1f),contentAlignment=Alignment.Center){Text(day,textAlign=TextAlign.Center,style=MaterialTheme.typography.labelSmall)}}}
  cells.chunked(7).forEach{week->
   Row(Modifier.fillMaxWidth()){
    (week+List<LocalDate?>(7-week.size){null}).forEach{date->
     Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).then(if(date!=null)Modifier.clickable{pick(date)}else Modifier),contentAlignment=Alignment.Center){
      if(date!=null){
       val has=events.any{occursOn(it,date)};val selectedDate=date==selected;val todayDate=date==today
       val circleModifier=Modifier.size(44.dp).then(if(todayDate&&!selectedDate)Modifier.border(2.dp,MaterialTheme.colorScheme.primary,CircleShape)else Modifier)
       if(selectedDate)Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(Modifier.size(44.dp),contentAlignment=Alignment.Center){Text(date.dayOfMonth.toString(),textAlign=TextAlign.Center,fontWeight=FontWeight.Bold)}}else Box(circleModifier,contentAlignment=Alignment.Center){Text(date.dayOfMonth.toString(),textAlign=TextAlign.Center,fontWeight=if(todayDate)FontWeight.Bold else FontWeight.Normal)}
       if(has)Box(Modifier.align(Alignment.BottomCenter).padding(bottom=3.dp).size(5.dp).background(MaterialTheme.colorScheme.primary,CircleShape))
      }
     }
    }
   }
  }
 }
}

@Composable private fun AgendaList(events:List<CalendarEvent>,open:(CalendarEvent)->Unit){if(events.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("No upcoming schedules")}else LazyColumn(contentPadding=PaddingValues(12.dp,8.dp,12.dp,96.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){items(events,key={it.id}){EventCard(it){open(it)}}}}
@Composable private fun EventCard(event:CalendarEvent,open:()->Unit){Card(Modifier.fillMaxWidth().clickable(onClick=open),shape=RoundedCornerShape(16.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.secondaryContainer){Text(categoryEmoji(event.category),Modifier.padding(10.dp))};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(event.title,fontWeight=FontWeight.SemiBold);Text(eventTimeLabel(event),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(event.category+(if(event.repeatRule!="none")" · ${event.repeatRule.replaceFirstChar{it.uppercase()}}"else""),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EventEditor(event:CalendarEvent?,defaultDate:LocalDate,onDismiss:()->Unit,onSave:(String,String?,String,String,String,Boolean,String,Int?)->Unit,onDelete:(CalendarEvent)->Unit){
 val context=LocalContext.current;val initial=event?.let{ZonedDateTime.parse(it.startAt).withZoneSameInstant(phZone)}?:defaultDate.atTime(9,0).atZone(phZone);val initialEnd=event?.let{ZonedDateTime.parse(it.endAt).withZoneSameInstant(phZone)}?:initial.plusHours(1)
 var title by remember(event?.id){mutableStateOf(event?.title.orEmpty())};var notes by remember(event?.id){mutableStateOf(event?.notes.orEmpty())};var category by remember(event?.id){mutableStateOf(event?.category?:"Other")};var date by remember(event?.id){mutableStateOf(initial.toLocalDate())};var startTime by remember(event?.id){mutableStateOf(initial.toLocalTime())};var endTime by remember(event?.id){mutableStateOf(initialEnd.toLocalTime())};var allDay by remember(event?.id){mutableStateOf(event?.allDay?:false)};var repeat by remember(event?.id){mutableStateOf(event?.repeatRule?:"none")};var reminder by remember(event?.id){mutableStateOf(event?.reminderMinutes)};var categoryMenu by remember{mutableStateOf(false)};var repeatMenu by remember{mutableStateOf(false)};var reminderMenu by remember{mutableStateOf(false)}
 fun pickDate(){DatePickerDialog(context,{_,y,m,d->date=LocalDate.of(y,m+1,d)},date.year,date.monthValue-1,date.dayOfMonth).show()};fun pickTime(current:LocalTime,set:(LocalTime)->Unit){TimePickerDialog(context,{_,h,m->set(LocalTime.of(h,m))},current.hour,current.minute,false).show()}
 AlertDialog(onDismissRequest=onDismiss,title={Text(if(event==null)"Add schedule"else"Edit schedule")},text={Column(Modifier.fillMaxWidth().heightIn(max=560.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){OutlinedTextField(title,{title=it.take(120)},Modifier.fillMaxWidth(),label={Text("Title")},singleLine=true);OutlinedTextField(notes,{notes=it.take(1000)},Modifier.fillMaxWidth(),label={Text("Notes (optional)")},maxLines=3);Box{OutlinedButton({categoryMenu=true},Modifier.fillMaxWidth()){Text("${categoryEmoji(category)} $category")};DropdownMenu(categoryMenu,{categoryMenu=false}){CalendarRepository.categories.forEach{c->DropdownMenuItem({Text("${categoryEmoji(c)} $c")},{category=c;categoryMenu=false})}}};OutlinedButton(::pickDate,Modifier.fillMaxWidth()){Text(date.format(dateFmt))};Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("All day",Modifier.weight(1f));Switch(allDay,{allDay=it})};if(!allDay)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({pickTime(startTime){startTime=it}},Modifier.weight(1f)){Text(startTime.format(timeFmt))};OutlinedButton({pickTime(endTime){endTime=it}},Modifier.weight(1f)){Text(endTime.format(timeFmt))}};Box{OutlinedButton({repeatMenu=true},Modifier.fillMaxWidth()){Text("Repeat: ${repeat.replaceFirstChar{it.uppercase()}}")};DropdownMenu(repeatMenu,{repeatMenu=false}){CalendarRepository.repeatRules.forEach{r->DropdownMenuItem({Text(r.replaceFirstChar{it.uppercase()})},{repeat=r;repeatMenu=false})}}};Box{OutlinedButton({reminderMenu=true},Modifier.fillMaxWidth()){Text("Reminder: ${reminderLabel(reminder)}")};DropdownMenu(reminderMenu,{reminderMenu=false}){listOf<Int?>(null,0,15,60,1440).forEach{r->DropdownMenuItem({Text(reminderLabel(r))},{reminder=r;reminderMenu=false})}}};if(event!=null)TextButton({onDelete(event)},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("Delete schedule")}}},confirmButton={Button(onClick={val start=if(allDay)date.atStartOfDay(phZone)else date.atTime(startTime).atZone(phZone);var end=if(allDay)date.plusDays(1).atStartOfDay(phZone).minusNanos(1)else date.atTime(endTime).atZone(phZone);if(end.isBefore(start))end=end.plusDays(1);onSave(title,notes.ifBlank{null},category,start.toOffsetDateTime().toString(),end.toOffsetDateTime().toString(),allDay,repeat,reminder)},enabled=title.isNotBlank()){Text("Save")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}

private fun eventDate(e:CalendarEvent)=ZonedDateTime.parse(e.startAt).withZoneSameInstant(phZone).toLocalDate()
private fun occursOn(e:CalendarEvent,date:LocalDate):Boolean{val start=eventDate(e);return when(e.repeatRule){"daily"->!date.isBefore(start);"weekly"->!date.isBefore(start)&&date.dayOfWeek==start.dayOfWeek;"monthly"->!date.isBefore(start)&&date.dayOfMonth==start.dayOfMonth.coerceAtMost(date.lengthOfMonth());"yearly"->!date.isBefore(start)&&date.month==start.month&&date.dayOfMonth==start.dayOfMonth.coerceAtMost(date.lengthOfMonth());else->date==start}}
private fun eventTimeLabel(e:CalendarEvent):String{val z=ZonedDateTime.parse(e.startAt).withZoneSameInstant(phZone);val end=ZonedDateTime.parse(e.endAt).withZoneSameInstant(phZone);return if(e.allDay)z.toLocalDate().format(dateFmt)+" · All day"else z.toLocalDate().format(dateFmt)+" · "+z.toLocalTime().format(timeFmt)+" – "+end.toLocalTime().format(timeFmt)}
private fun reminderLabel(v:Int?)=when(v){null->"None";0->"At event time";15->"15 minutes before";60->"1 hour before";1440->"1 day before";else->"$v minutes before"}
private fun categoryEmoji(c:String)=when(c){"Family"->"👨‍👩‍👧‍👦";"Work"->"💼";"Date"->"❤️";"Birthday"->"🎂";"Appointment"->"📌";"Reminder"->"🔔";else->"📅"}
