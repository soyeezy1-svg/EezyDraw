package com.eezydraw.storage
import android.graphics.*
import com.eezydraw.core.model.*
import kotlin.math.*
fun paintIssueMarkups(canvas:Canvas,marks:List<IssueMarkup>,width:Int,height:Int) {
 for(m in marks){val pts=m.points.map {PointF((it.x*width).toFloat(),(it.y*height).toFloat())};if(pts.isEmpty())continue
  val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=m.colour.toInt();strokeWidth=(m.thickness*width/1000).toFloat();style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
  val a=pts.first();val b=pts.last();val rect=RectF(min(a.x,b.x),min(a.y,b.y),max(a.x,b.x),max(a.y,b.y))
  when(m.tool){
   MarkupTool.RECTANGLE->canvas.drawRect(rect,paint)
   MarkupTool.CIRCLE->canvas.drawCircle(a.x,a.y,hypot(b.x-a.x,b.y-a.y),paint)
   MarkupTool.TEXT->{paint.style=Paint.Style.FILL;paint.textSize=max(14f,paint.strokeWidth*6);canvas.drawText(m.text,a.x,a.y,paint)}
   MarkupTool.CLOUD->{val path=Path();val r=max(8f,width/70f);fun scallop(x:Float,y:Float,dx:Float,dy:Float){val n=max(1,(hypot(dx,dy)/r).toInt());for(i in 0 until n){val sx=x+dx*i/n;val sy=y+dy*i/n;val ex=x+dx*(i+1)/n;val ey=y+dy*(i+1)/n;path.quadTo((sx+ex)/2+dy/n*.4f,(sy+ey)/2-dx/n*.4f,ex,ey)}}
    path.moveTo(rect.left,rect.top);scallop(rect.left,rect.top,rect.width(),0f);scallop(rect.right,rect.top,0f,rect.height());scallop(rect.right,rect.bottom,-rect.width(),0f);scallop(rect.left,rect.bottom,0f,-rect.height());path.close();canvas.drawPath(path,paint)}
   else->{if(m.tool==MarkupTool.HIGHLIGHTER){paint.alpha=64;paint.strokeWidth*=6};val path=Path();path.moveTo(a.x,a.y);pts.drop(1).forEach {path.lineTo(it.x,it.y)};if(m.tool==MarkupTool.ARROW)canvas.drawLine(a.x,a.y,b.x,b.y,paint)else canvas.drawPath(path,paint)
    if(m.tool==MarkupTool.ARROW){val theta=atan2((b.y-a.y).toDouble(),(b.x-a.x).toDouble());val len=max(12f,paint.strokeWidth*5);for(sign in listOf(-1,1))canvas.drawLine(b.x,b.y,b.x-(len*cos(theta+sign*.5)).toFloat(),b.y-(len*sin(theta+sign*.5)).toFloat(),paint)}}
  }
 }
}
