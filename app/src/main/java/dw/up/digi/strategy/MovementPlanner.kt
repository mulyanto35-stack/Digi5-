package dw.up.digi.strategy

import dw.up.digi.detection.Cell
import dw.up.digi.detection.CellScores
import java.util.PriorityQueue

enum class Direction(val dr:Int,val dc:Int){RIGHT(0,1),DOWN(1,0),UP(-1,0),LEFT(0,-1)}
enum class ActionKind{MOVE,ATTACK,DASH}
data class Action(val kind:ActionKind,val target:Cell,val direction:Direction,val reason:String)

object MovementPlanner{
 /** Pyramids are not dead ends: with claws, walking into one destroys the obstacle. That costs a
  *  claw, an additional tap and the smash animation, so detours of up to three cells are cheaper
  *  than a breakthrough. */
 private const val OBSTACLE_COST=4
 /** This many claws are kept as a reserve for a genuine dead end. As long as no more than that
  *  are available, every pyramid is avoided - even on large detours. */
 const val CLAW_RESERVE=1
 /** How far the bot plans ahead to the right when no collectable is visible. */
 const val LOOKAHEAD=3
 /** Dash only pays off with more than this many pyramids in the next [LOOKAHEAD] cells. */
 private const val DASH_PYRAMIDS=2
 /** Outside a blockade, only Dash if more than this many charges are left. */
 private const val DASH_RESERVE=1
 /** Weight of the look-ahead per free cell. Must be large enough to outvote the rightward bonus
  *  when the row is built up. */
 private const val LOOKAHEAD_WEIGHT=20
 /** Penalty for a cell that must first be smashed. Must clearly outweigh the rightward bonus,
  *  otherwise the bot rams the pyramid although one row further everything is free. */
 private const val BLOCKED_PENALTY=90
 /** From this value on, a cell counts as a collectable. */
 private const val ITEM_SCORE=.06
 /** From this orange share on, the collectable is an energy orb and not a claw. */
 private const val ENERGY_ORANGE=.06
 /** Energy orbs disappear on their own, claws stay lying around. A detour of up to this many
  *  cells is therefore worth it to collect the energy first. */
 private const val ENERGY_DETOUR=3
 /** Penalty for a cell from which the right column is no longer reachable. Must outweigh the
  *  rightward bonus including the look-ahead, otherwise the bot keeps walking into the pocket. */
 private const val DEAD_END_PENALTY=250

 private data class Node(val cost:Int,val cell:Cell,val path:List<Action>):Comparable<Node>{override fun compareTo(o:Node)=cost.compareTo(o.cost)}

 /**
  * @param claws read claw supply, `null` if the number was not reliably read. Both are
  * treated equally cautiously: smashing is then only allowed if nothing else works at all.
  */
 fun choose(
  player:Cell,
  cells:Map<Cell,CellScores>,
  history:List<Cell>,
  dashAvailable:Boolean=false,
  preview:Map<Cell,CellScores> = emptyMap(),
  forbiddenObstacles:Set<Cell> = emptySet(),
  dashCharges:Int=0,
  stuck:Boolean=false,
  claws:Int?=null,
  settings:DwsNavigationSettings=DwsNavigationSettings(),
 ):Action?{
  val energyVisible=cells.any{(cell,score)->cell!=player&&score.orange>ENERGY_ORANGE}
  // The optional Supporter test profile deliberately spends every *confirmed* dash charge when no
  // energy is visible. AutoMoveController supplies zero when the HUD counter is unreadable, so this
  // branch can never spam an assumed fallback value.
  if(settings.dashSpamUntilZero&&!energyVisible&&dashAvailable&&dashCharges>0){
   return Action(ActionKind.DASH,Cell(player.row,minOf(4,player.col+LOOKAHEAD)),Direction.RIGHT,"Dash-Spam bis HUD 0")
  }

  // Always search the complete visible board without spending a resource first. A long detour is
  // still preferable to attacking or dashing; resource use is considered only if this search
  // cannot reach an item or the right side at all.
  route(player,cells,history,preview,forbiddenObstacles,false,settings)?.let{return it}

  val ahead=(1..LOOKAHEAD).map{Cell(player.row,player.col+it)}.filter{it.col<=4}
  val pyramidsAhead=ahead.count{cells[it]?.obstacle()==true}
  if(dashAvailable&&dashCharges>DASH_RESERVE&&pyramidsAhead>=DASH_PYRAMIDS&&!energyVisible){
   return Action(ActionKind.DASH,Cell(player.row,minOf(4,player.col+LOOKAHEAD)),Direction.RIGHT,"erzwungener Ausweg: $pyramidsAhead Pyramiden")
  }

  // There is no free route. Prefer one deliberate attack along the cheapest breakout path.
  if(claws==null||claws>0){
   route(player,cells,history,preview,forbiddenObstacles,true,settings)?.let{action->
    return if(action.kind==ActionKind.ATTACK)action.copy(reason=if(claws!=null&&claws<=CLAW_RESERVE) "Reserve: kein freier Weg drumherum" else "kein freier Weg drumherum") else action
   }
  }

  // With no usable claw, a confirmed dash is the final escape option. Never dash merely because
  // the short-term position history oscillates while a normal route still exists.
  if(dashAvailable&&dashCharges>0){
   return Action(ActionKind.DASH,Cell(player.row,minOf(4,player.col+LOOKAHEAD)),Direction.RIGHT,"Notausgang ohne Kralle")
  }
  return null
 }
 private fun allowedDirections(settings:DwsNavigationSettings):List<Direction> = if(settings.allowLeft) Direction.entries else Direction.entries.filterNot{it==Direction.LEFT}

 private fun route(
  player:Cell,
  cells:Map<Cell,CellScores>,
  history:List<Cell>,
  preview:Map<Cell,CellScores>,
  forbiddenObstacles:Set<Cell>,
  mayBreak:Boolean,
  settings:DwsNavigationSettings,
 ):Action?{
  val obstacles=cells.filterValues{it.obstacle()}.keys
  val blockedCells=if(mayBreak)forbiddenObstacles else forbiddenObstacles+obstacles

  // Forward-only test profile: a reachable energy target still wins. Otherwise the planner keeps
  // the current row and either moves right or, during the resource-backed pass, attacks the pyramid
  // directly in front. No generic item route can pull the player sideways or backwards here.
  if(settings.forceForwardAttack){
   val energy=cells.filter{(cell,score)->cell!=player&&score.orange>ENERGY_ORANGE}.keys
   if(settings.betterEnergyCollect){
    val sameColumn=energy.filter{it.col==player.col}.toSet()
    forwardItemPath(player,sameColumn,cells,blockedCells,settings)?.firstOrNull()?.let{return it.copy(reason="Energie in eigener Spalte zuerst")}
   }
   forwardItemPath(player,energy,cells,blockedCells,settings)?.firstOrNull()?.let{return it.copy(reason="Energie direkt")}
   val right=Cell(player.row,player.col+1)
   cells[right]?.let{score->
    if(right !in forbiddenObstacles&&(!score.obstacle()||mayBreak)){
     return Action(if(score.obstacle())ActionKind.ATTACK else ActionKind.MOVE,right,Direction.RIGHT,if(score.obstacle())"Vorwaertshindernis angreifen" else "Nur vorwaerts")
    }
   }
   return null
  }
  // Once the free search has proved that the player is trapped, follow one stable breakout path
  // toward the right edge. This prevents wandering around the remaining two or three free cells.
  if(mayBreak){
   val rightEdge=cells.keys.filter{it.col==4}.toSet()
   shortestPath(player,rightEdge,cells,forbiddenObstacles,settings)?.firstOrNull()?.let{return it.copy(reason="Ausbruchspfad")}
  }

  itemPath(player,cells,blockedCells,settings)?.let{path->
   val goal=path.last().target
   val energy=(cells[goal]?.orange?:0.0)>ENERGY_ORANGE
   return path.first().copy(reason=if(energy)"Energie zuerst" else "item route")
  }

  // If no item remains inside the reachable component and the right edge cannot be reached without
  // a pyramid, report failure so choose() may consider exactly one resource-backed escape.
  if(!mayBreak&&!escapesRight(player,cells,blockedCells,false,settings))return null

  val oscillating=history.size>=4&&history.takeLast(4).let{it[0]==it[2]&&it[1]==it[3]}
  val previous=history.dropLast(1).lastOrNull()
  val candidates=allowedDirections(settings).mapNotNull{d->
   val n=Cell(player.row+d.dr,player.col+d.dc);val scoreCell=cells[n]?:return@mapNotNull null;if(n in blockedCells)return@mapNotNull null
   val blocked=scoreCell.obstacle()
   var score=when(d){Direction.RIGHT->100;Direction.DOWN->20;Direction.UP->15;Direction.LEFT->-40}
   if(!blocked)score+=(scoreCell.highlight*20).toInt()
   score+=freeAhead(n,cells)*LOOKAHEAD_WEIGHT
   if(blocked)score-=BLOCKED_PENALTY
   if(!blocked&&!escapesRight(n,cells,blockedCells+player,mayBreak,settings))score-=DEAD_END_PENALTY
   if(n==previous)score-=80;if(oscillating&&n==history[history.lastIndex-1])score-=200
   val look=preview[Cell(n.row,5)];if(look!=null&&look.pyramid>.17&&look.item<=.06)score-=35;if((look?.item?:0.0)>.06)score+=45
   score to Action(if(blocked)ActionKind.ATTACK else ActionKind.MOVE,n,d,if(blocked)"Pyramide zerschlagen" else "Strecke rechts")
  }
  // Eine sichtbare Sackgasse ist keine "schlechte" Route, sondern gar keine Route. Sobald
  // wenigstens ein Nachbar weiterhin zur rechten Brettkante fuehrt, werden alle Taschen hart
  // ausgeschlossen. So laeuft die Figur nicht erst in die Ecke und danach denselben Weg zurueck.
  val escaping=candidates.filter{(_,action)->action.kind!=ActionKind.MOVE||escapesRight(action.target,cells,blockedCells+player,mayBreak,settings)}
  return (escaping.ifEmpty{candidates}).maxByOrNull{it.first}?.second
 }
 /** How many of the next [LOOKAHEAD] cells to the right of [from] are freely walkable, normalised
  *  to [LOOKAHEAD]. Fewer cells are available at the right edge - without normalisation the edge
  *  would otherwise be systematically rated worse than the middle of the board. */
 private fun freeAhead(from:Cell,cells:Map<Cell,CellScores>):Int{
  val ahead=(1..LOOKAHEAD).map{Cell(from.row,from.col+it)}.filter{it.col<=4}
  if(ahead.isEmpty())return LOOKAHEAD
  return ahead.count{cells[it]?.obstacle()!=true}*LOOKAHEAD/ahead.size
 }

 /**
  * Path to the nearest collectable - energy orbs first. They vanish after a short time on their
  * own, while claws stay lying around. Without this preference the bot always took only the
  * nearest symbol and walked past an already visible energy orb until it was gone.
  */
 private fun itemPath(player:Cell,cells:Map<Cell,CellScores>,blocked:Set<Cell>,settings:DwsNavigationSettings):List<Action>?{
  val items=cells.filter{(cell,s)->
   cell!=player&&s.item>ITEM_SCORE&&(!settings.collectOnlyEnergy||s.orange>ENERGY_ORANGE)
  }.keys
  if(items.isEmpty())return null
  val any=forwardItemPath(player,items,cells,blocked,settings)
  val energy=items.filter{(cells[it]?.orange?:0.0)>ENERGY_ORANGE}.toSet()
  if(energy.isEmpty())return any
  if(settings.betterEnergyCollect){
   val sameColumn=energy.filter{it.col==player.col}.toSet()
   forwardItemPath(player,sameColumn,cells,blocked,settings)?.let{return it}
  }
  val toEnergy=forwardItemPath(player,energy,cells,blocked,settings)?:return any
  if(any==null)return toEnergy
  return if(toEnergy.size<=any.size+ENERGY_DETOUR)toEnergy else any
 }

 /**
  * For items of the same kind the shortest single path does not simply win. A target to the left
  * of the player gets a clear backtracking penalty, while targets further right are preferred.
  * As a result the bot collects a visible row in walking direction instead of turning around for
  * a nearby item and losing the items ahead through the scrolling.
  */
 private fun forwardItemPath(player:Cell,targets:Set<Cell>,cells:Map<Cell,CellScores>,blocked:Set<Cell>,settings:DwsNavigationSettings):List<Action>? =
  targets.mapNotNull{target->
   shortestPath(player,setOf(target),cells,blocked,settings)?.let{path->
    val backwards=(player.col-target.col).coerceAtLeast(0)
    // Entfernung bleibt das Hauptkriterium; rechts entscheidet nur bei gleichem Wegwert.
    Triple(path.size+backwards*6,-target.col,path)
   }
  }.minWithOrNull(compareBy<Triple<Int,Int,List<Action>>>{it.first}.thenBy{it.second})?.third

 /**
  * Is the right column still reachable from [from] at all? Only this distinguishes a pocket
  * enclosed by pyramids from a normal detour - at 5x5 the board is small enough that the complete
  * search is cheaper than any heuristic.
  */
 private fun escapesRight(from:Cell,cells:Map<Cell,CellScores>,blocked:Set<Cell>,passObstacles:Boolean,settings:DwsNavigationSettings):Boolean{
  if(from.col>=4)return true
  val seen=mutableSetOf(from);val queue=ArrayDeque(listOf(from))
  while(queue.isNotEmpty()){
   val c=queue.removeFirst()
   if(c.col>=4)return true
   for(d in allowedDirections(settings)){
    val n=Cell(c.row+d.dr,c.col+d.dc)
    if(n in seen||n in blocked)continue
    val s=cells[n]?:continue
    if(s.obstacle()&&!passObstacles)continue
    seen+=n;queue+=n
   }
  }
  return false
 }

 private fun shortestPath(start:Cell,targets:Set<Cell>,cells:Map<Cell,CellScores>,forbidden:Set<Cell>,settings:DwsNavigationSettings):List<Action>?{
  val q=PriorityQueue<Node>();q+=Node(0,start,emptyList());val best=mutableMapOf(start to 0)
  while(q.isNotEmpty()){val n=q.remove();if(n.cost!=best[n.cell])continue;if(n.cell in targets&&n.path.isNotEmpty())return n.path
   for(d in allowedDirections(settings)){val next=Cell(n.cell.row+d.dr,n.cell.col+d.dc);if(next in forbidden)continue;val s=cells[next]?:continue;val obstacle=s.obstacle();val cost=n.cost+(if(obstacle)OBSTACLE_COST else 1)
    if(cost<(best[next]?:999)){best[next]=cost;q+=Node(cost,next,n.path+Action(if(obstacle)ActionKind.ATTACK else ActionKind.MOVE,next,d,"item route"))}}}
  return null
 }

 /**
  * Like [choose], but additionally returns the next steps of the same route so they can be
  * tapped one after another without re-analysing the image.
  *
  * Steps are only bundled if a misstep would have no consequences: if the game swallows a tap,
  * the whole chain shifts by one cell. Therefore the corridor including one safety column must
  * be free of pyramids - otherwise a swallowed tap would consume a claw. When in doubt, it stays
  * a single step.
  */
 fun plan(
  player:Cell,
  cells:Map<Cell,CellScores>,
  history:List<Cell>,
  dashAvailable:Boolean=false,
  preview:Map<Cell,CellScores> = emptyMap(),
  forbiddenObstacles:Set<Cell> = emptySet(),
  dashCharges:Int=0,
  stuck:Boolean=false,
  claws:Int?=null,
  maxSteps:Int=1,
  settings:DwsNavigationSettings=DwsNavigationSettings(),
 ):List<Action>{
  val first=choose(player,cells,history,dashAvailable,preview,forbiddenObstacles,dashCharges,stuck,claws,settings)?:return emptyList()
  val single=listOf(first)
  if(maxSteps<=1||first.kind!=ActionKind.MOVE)return single
  val mayBreak=claws!=null&&claws>CLAW_RESERVE
  val blocked=forbiddenObstacles+(if(mayBreak)emptySet() else cells.filterValues{it.obstacle()}.keys)
   val itemRoute=itemPath(player,cells,blocked,settings)
   val route=if(itemRoute!=null){
    if(itemRoute.first().target!=first.target)return single
    itemRoute.takeWhile{it.kind==ActionKind.MOVE}.take(maxSteps)
  } else {
   if(first.direction!=Direction.RIGHT)return single
   val out=mutableListOf(first)
   while(out.size<maxSteps){
    val n=Cell(first.target.row,out.last().target.col+1)
    if(n.col>4)break
    val s=cells[n]?:break
    if(s.obstacle()||n in blocked)break
    out+=Action(ActionKind.MOVE,n,Direction.RIGHT,"Strecke rechts")
   }
   out
  }
  if(route.size<=1)return single
  return if(corridorClear(player,route,cells))route else single
 }

 /** All cells of the route plus one column before and behind each must be free. */
 private fun corridorClear(player:Cell,route:List<Action>,cells:Map<Cell,CellScores>):Boolean{
  val rows=(route.map{it.target.row}+player.row).toSet()
  val cols=(route.map{it.target.col}+player.col)
  return rows.all{r->(cols.min()..cols.max()+1).all{c->c>4||cells[Cell(r,c)]?.obstacle()!=true}}
 }
}
