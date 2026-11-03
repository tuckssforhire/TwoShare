package com.twoshare.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.NowTogetherAttribution
import com.twoshare.app.data.NowTogetherRepository
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.FeedPost

@Composable
fun FeedPostCaption(post:FeedPost,repository:TwoShareRepository){
    val attribution by produceState<NowTogetherAttribution?>(initialValue=null,key1=post.id){
        value=runCatching{NowTogetherRepository(repository).attributionForPost(post.id)}.getOrNull()
    }
    val nt=attribution
    if(nt==null){
        post.caption?.takeIf{it.isNotBlank()}?.let{Text(it,Modifier.padding(horizontal=14.dp,vertical=11.dp),style=MaterialTheme.typography.bodyMedium)}
    }else{
        Column(Modifier.padding(horizontal=14.dp,vertical=11.dp)){
            Text("Now Together ❤️",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
            nt.initiatorCaption?.takeIf{it.isNotBlank()}?.let{
                Spacer(Modifier.height(7.dp));Text(nt.initiatorName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.labelLarge);Text(it,style=MaterialTheme.typography.bodyMedium)
            }
            nt.partnerCaption?.takeIf{it.isNotBlank()}?.let{
                Spacer(Modifier.height(7.dp));Text(nt.partnerName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.labelLarge);Text(it,style=MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
