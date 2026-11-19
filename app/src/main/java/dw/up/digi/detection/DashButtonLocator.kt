package dw.up.digi.detection

/**
 * Finds the blue Dash button below the playfield. Absolute pixel coordinates would depend on
 * the device and panel, so the button is located by its colour: it is the large, strongly
 * cyan-blue area below the grid; when several blue buttons exist, the left one wins. The dark
 * blue heading text is excluded by the green lower bound.
 */
object DashButtonLocator{
 private const val MIN_SAMPLES=350
 private const val STEP=2
 /** Matching pixels are grouped into coarse cells before searching for connected regions. */
 private const val CELL=12
 private const val CELL_MIN_HITS=6
 /** The newer UI has TWO similar round blue buttons. The Dash button is the LEFTMOST one.
  *  If the wrong neighbouring button gets tapped, set this to false (pick the rightmost). */
 private const val DASH_IS_LEFTMOST=true

 private class Blob(val x:Double,val y:Double,val n:Int)

 fun locate(width:Int,height:Int,argb:IntArray,bounds:GridBounds):Pair<Float,Float>?{
  val y0=(bounds.bottom+ (bounds.bottom-bounds.top)/20).coerceIn(0,height-1)
  if(y0>=height-10)return null
  val cols=(width+CELL-1)/CELL;val rows=(height+CELL-1)/CELL
  val hits=IntArray(cols*rows);val sx=LongArray(cols*rows);val sy=LongArray(cols*rows)
  var y=y0
  while(y<height){
   var x=0
   while(x<width){
    val p=argb[y*width+x];val r=p shr 16 and 255;val g=p shr 8 and 255;val b=p and 255
    if(b>195&&g>140&&r<140&&b>r+80){val i=(y/CELL)*cols+x/CELL;hits[i]++;sx[i]+=x;sy[i]+=y}
    x+=STEP
   }
   y+=STEP
  }
  // Bagian-bagian biru yang saling terhubung (8 tetangga) = satu tombol.
  val seen=BooleanArray(hits.size);val blobs=mutableListOf<Blob>()
  fun blobOf(cells:List<Int>):Blob?{
   var n=0;var X=0L;var Y=0L
   for(c in cells){n+=hits[c];X+=sx[c];Y+=sy[c]}
   return if(n>=MIN_SAMPLES)Blob(X.toDouble()/n,Y.toDouble()/n,n) else null
  }
  for(start in hits.indices){
   if(seen[start]||hits[start]<CELL_MIN_HITS)continue
   val cells=mutableListOf<Int>();val stack=ArrayDeque<Int>();stack.add(start);seen[start]=true
   while(stack.isNotEmpty()){
    val c=stack.removeLast();cells+=c;val cr=c/cols;val cc=c%cols
    for(dr in -1..1)for(dc in -1..1){
     val nr=cr+dr;val nc=cc+dc
     if(nr<0||nc<0||nr>=rows||nc>=cols)continue
     val ni=nr*cols+nc
     if(!seen[ni]&&hits[ni]>=CELL_MIN_HITS){seen[ni]=true;stack.add(ni)}
    }
   }
   val minC=cells.minOf{it%cols};val maxC=cells.maxOf{it%cols};val minR=cells.minOf{it/cols};val maxR=cells.maxOf{it/cols}
   val w=maxC-minC+1;val h=maxR-minR+1
   if(w>h*1.7){
    // Dua tombol yang saling menempel terbaca sebagai satu gumpalan lebar: dibelah di tengah.
    val (left,right)=cells.partition{(it%cols)*2<=minC+maxC}
    blobOf(left)?.let{blobs+=it};blobOf(right)?.let{blobs+=it}
   } else blobOf(cells)?.let{blobs+=it}
  }
  val pick=(if(DASH_IS_LEFTMOST)blobs.minByOrNull{it.x} else blobs.maxByOrNull{it.x})?:return null
  return pick.x.toFloat() to pick.y.toFloat()
 }

 /** Safe relative centre point, used only by the explicit experimental Dash-spam fallback. */
 fun relativeFallback(width:Int,height:Int,bounds:GridBounds):Pair<Float,Float>?{
  val gridWidth=(bounds.right-bounds.left).toFloat();val gridHeight=(bounds.bottom-bounds.top).toFloat()
  if(gridWidth<=0f||gridHeight<=0f)return null
  val x=bounds.left+gridWidth*.66f;val y=bounds.bottom+gridHeight*.60f
  if(x !in 0f..width.toFloat()||y !in 0f..height.toFloat())return null
  return x to y
 }
}
