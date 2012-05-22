/**
*  Copyright (c) 2010, Aemon Cannon
*  All rights reserved.
*
*  Redistribution and use in source and binary forms, with or without
*  modification, are permitted provided that the following conditions are met:
*      * Redistributions of source code must retain the above copyright
*        notice, this list of conditions and the following disclaimer.
*      * Redistributions in binary form must reproduce the above copyright
*        notice, this list of conditions and the following disclaimer in the
*        documentation and/or other materials provided with the distribution.
*      * Neither the name of ENSIME nor the
*        names of its contributors may be used to endorse or promote products
*        derived from this software without specific prior written permission.
*
*  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
*  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
*  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
*  DISCLAIMED. IN NO EVENT SHALL Aemon Cannon BE LIABLE FOR ANY
*  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
*  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
*  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
*  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
*  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
*  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.ensime.server
import java.io.File
import org.ensime.config.ProjectConfig
import org.ensime.model._
import org.ensime.util._
import scala.actors.Actor._
import scala.actors.Actor
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.tools.nsc.interactive.{ FreshRunReq, CompilerControl, Global, MissingResponse }
import scala.tools.nsc.util._
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.reporters.Reporter
import scala.tools.nsc.symtab.Types
import scala.tools.nsc.util.{ Position, RangePosition, SourceFile }
import scala.tools.nsc.Settings
import scala.tools.refactoring.analysis.GlobalIndexes

trait RichCompilerControl extends CompilerControl with RefactoringControl with CompletionControl {
  self: RichPresentationCompiler =>

  def askStructure(f: SourceFile) = { 
    val x = new Response[Tree]()
    super.askParsedEntered(f, false, x)
    val structure = x.get
 
   def formatTree(tree: Tree): Option[SExp] = tree match {

     case ClassDef(mods, name, tparams, impl) => {
       val children = tree.children.head.children.foldLeft[Option[SExp]](None)((str, arg) => formatTree(arg) match { 
	 case Some(sexp) => str match { 
	   case Some(SExpList(list)) => Some(SExpList(list.toList :+ sexp))
	   case _ => Some(SExpList(List(sexp)))
	 }
	 case _ => str
       })
       
       val str = 
	 { if (mods.isPrivate) "private " else if (mods.isProtected) "protected " else "" } +
       	 { if (mods.isSealed) "sealed " else "" } + 
	 { if (mods.hasAbstractFlag && !mods.hasTraitFlag) "absctract " else "" } + 
	 { if (mods.isFinal) "final " else "" } +
	 { if (mods.isCase) "case " else "" } +
	 { if (mods.isOverride) "override " else "" } +
	 { if (mods.isImplicit) "implicit " else ""} +
	 { if (mods.hasTraitFlag) "trait " else "class "} +
	 name + stringListTParams(tparams)

       Some(
	 SExpAssoc(
	   StringAtom(str),
	   children match { 
	     case Some(sexp) => sexp
	     case _ => IntAtom(tree.pos.point)
	   }
	 ))
     }
      
     case DefDef (mods, name, tparams, vparamss, tpt, rhs) => Some(
       SExpAssoc(
	 "%sdef %s%s%s: %s".format({ if (mods.isPrivate) "private " else if (mods.isProtected) "protected " else "" } +
				   { if (mods.isFinal) "final " else "" } +
				   { if (mods.isOverride) "override " else ""} +
				   { if (mods.isImplicit) "implicit " else ""}, 
				   name, stringListTParams(tparams), vparamss.foldLeft("")((str, arg) => str + stringListVParam(arg)), tpt.symbol.name),
	 tree.pos.point
       ))
     
     
     //the object symbol
     case ModuleDef (mods, name, impl) => {
       val s = tree.children.head.children.foldLeft[Option[SExp]](None)((str, arg) => formatTree(arg) match { 
	 case Some(sexp) => str match { 
	   case Some(SExpList(list)) => Some(SExpList(list.toList :+ sexp))
	   case _ => Some(SExpList(List(sexp)))
	 }
	 case _ => str
       })

       Some(
	 SExpAssoc(
	   StringAtom("%sobject %s".format({ if (mods.isPrivate) "private " else if (mods.isProtected) "protected " else "" } + 
					   { if (mods.isCase) "case " else "" }, name)),
	   s match { 
	     case Some(sexp) => sexp
	     case _ => IntAtom(tree.pos.point)
	   }
	 ))
     }
     
     case PackageDef (pid: RefTree, stats: List[Tree]) => {
       println("**package " + pid.toString)
       None
     }

     case ValDef (mods, name, tpt, rhs) => { 
       println("**val " + name.toString)
       None
     }

     case TypeDef (mods, name, tparams, rhs) => { 
       println("**type " + name.toString)
       None
     }
     
     case _ => {
       println("**unkown")
       None
     }
   }
   
   
   def stringListVParam(vparams: List[ValDef]): String = {
     if (vparams.isEmpty) 
       "()"
     else
       "(" + vparams.tail.foldLeft(stringValDef(vparams.head))((str, arg) => str + ", " + stringValDef(arg)) + ")"
   }

    def stringListTParams(tparams: List[TypeDef]): String = {
      if (tparams.isEmpty)
	""
      else
	"[" + tparams.tail.foldLeft(stringTypeDef(tparams.head))((str, arg) => str + ", " + stringTypeDef(arg)) + "]"
    }

    def stringValDef(tree: Tree): String = tree match {
      case ValDef(mods, name, tpt, rhs) => tpt.symbol.name.toString
      case _ => ""
    }
   

    def stringTypeDef(tree: Tree): String = tree match {
      case TypeDef(mods, name, tparams, rhs) => name.toString + stringListTParams(tparams)
      case _ => ""
    }

    if (structure.isLeft) {
      val topClass = structure.left.get.children.filter(arg => arg.symbol.isClass || arg.symbol.isAbstractClass || arg.symbol.isImplClass)

      topClass.foldLeft[Option[SExp]](None)((str, arg) => formatTree(arg) match { 
	 case Some(sexp) => str match { 
	   case Some(SExpList(list)) => Some(SExpList(list.toList :+ sexp))
	   case _ => Some(SExpList(List(sexp)))
	 }
	 case _ => str
       })
    }
    else
      None
  }

  def askOption[A](op: => A): Option[A] =
  try {
    Some(ask(() => op))
  }
  catch {
    case fi: FailedInterrupt =>
    fi.getCause() match {
      case e @ InvalidCompanions(c1, c2) =>
      richReporter.warning(c1.pos, e.getMessage)
      None
      case e: InterruptedException =>
      Thread.currentThread().interrupt()
      e.printStackTrace()
      System.err.println("interrupted exception in askOption")
      None
      case e =>
      e.printStackTrace()
      System.err.println("Error during askOption", e)
      None
    }
    case e =>
    e.printStackTrace()
    System.err.println("Error during askOption", e)
    None
  }

  def askSymbolInfoAt(p: Position): Option[SymbolInfo] = 
  askOption(symbolAt(p)).flatMap(_.map(SymbolInfo(_)))

  def askTypeInfoAt(p: Position): Option[TypeInfo] = 
  askOption(typeAt(p)).flatMap(_.map(TypeInfo(_)))

  def askTypeInfoById(id: Int): Option[TypeInfo] = 
  askOption(typeById(id)).flatMap(_.map(TypeInfo(_)))

  def askTypeInfoByName(name: String): Option[TypeInfo] = 
  askOption(typeByName(name)).flatMap(_.map(TypeInfo(_)))

  def askTypeInfoByNameAt(name: String, p: Position): Option[TypeInfo] = {
    val nameSegs = name.split("\\.")
    val firstName: String = nameSegs.head
    val x = new Response[List[Member]]()
    askScopeCompletion(p, x)
    (for(
	members <- x.get.left.toOption;
	infos <- askOption{
	  val roots = filterMembersByPrefix(members, firstName, true, true).map{ _.sym }
	  val restOfPath = nameSegs.drop(1).mkString(".")
	  val syms = roots.flatMap{symsAtQualifiedPath(restOfPath,_)}
	  syms.filter { _.tpe != NoType }.headOption.map { sym => TypeInfo(sym.tpe) }
	}
      ) yield infos).getOrElse(None)
  }


  def askCallCompletionInfoById(id: Int): Option[CallCompletionInfo] = 
  askOption(typeById(id)).flatMap(_.map(CallCompletionInfo(_)))

  def askPackageByPath(path: String): Option[PackageInfo] = 
  askOption(PackageInfo.fromPath(path))

  def askReloadFile(f: SourceFile) {
    askReloadFiles(List(f))
  }

  def askReloadFiles(files: Iterable[SourceFile]) {
    val x = new Response[Unit]()
    askReload(files.toList, x)
    x.get
  }

  def askRemoveAllDeleted() = askOption(removeAllDeleted())

  def askRemoveDeleted(f: File) = askOption(removeDeleted(AbstractFile.getFile(f)))

  def askReloadAllFiles() = {
    val all = ((config.sourceFilenames.map(getSourceFile(_))) ++
      allSources).toSet.toList
    askReloadFiles(all)
  }

  def askInspectTypeById(id: Int): Option[TypeInspectInfo] = 
  askOption(typeById(id)).flatMap(_.map(inspectType(_)))

  def askInspectTypeAt(p: Position): Option[TypeInspectInfo] = 
  askOption(inspectTypeAt(p)).flatMap(os => os)

  def askCompletePackageMember(path: String, prefix: String): List[CompletionInfo] = 
  askOption(completePackageMember(path, prefix)).getOrElse(List())

  def askCompletionsAt(p: Position, maxResults: Int, caseSens: Boolean): CompletionInfoList = 
  completionsAt(p, maxResults, caseSens)

  def askReloadAndTypeFiles(files: Iterable[SourceFile]) = 
  askOption(reloadAndTypeFiles(files))

  def askUsesOfSymAtPoint(p: Position): List[RangePosition] = 
  askOption(usesOfSymbolAtPoint(p).toList).getOrElse(List())

  def askSymbolDesignationsInRegion(p: RangePosition, tpes: List[scala.Symbol]): SymbolDesignations = 
  askOption(symbolDesignationsInRegion(p, tpes)).getOrElse(SymbolDesignations("", List()))

  def askClearTypeCache() = clearTypeCache

  def askNotifyWhenReady() = ask(setNotifyWhenReady)

  def sourceFileForPath(path: String) = getSourceFile(path)

}

class RichPresentationCompiler(
  settings: Settings,
  val richReporter: Reporter,
  var parent: Actor,
  var indexer: Actor,
  val config: ProjectConfig) extends Global(settings, richReporter)
with NamespaceTraversal with ModelBuilders with RichCompilerControl
with RefactoringImpl with IndexerInterface with SemanticHighlighting with Completion with Helpers{

  import ModelHelpers._

  private val symsByFile = new mutable.HashMap[AbstractFile, mutable.LinkedHashSet[Symbol]] {
    override def default(k: AbstractFile) = {
      val v = new mutable.LinkedHashSet[Symbol]
      put(k, v)
      v
    }
  }

  override val debugIDE: Boolean = true
  override val verboseIDE: Boolean = true
  private val newTopLevelSyms = new mutable.LinkedHashSet[Symbol]

  def activeUnits(): List[CompilationUnit] = {
    val invalidSet = toBeRemoved.synchronized { toBeRemoved.toSet }
    unitOfFile.filter { kv => !invalidSet.contains(kv._1) }.values.toList
  }

  /** Called from typechecker every time a top-level class or object is entered.*/
  override def registerTopLevelSym(sym: Symbol) {
    super.registerTopLevelSym(sym)
    symsByFile(sym.sourceFile) += sym
    newTopLevelSyms += sym
  }

  /**
  * Remove symbols defined by files that no longer exist.
  * Note that these symbols will not be collected by
  * syncTopLevelSyms, since the units in question will
  * never be reloaded again.
  */
  def removeAllDeleted() {
    allSources = allSources.filter { _.file.exists }
    val deleted = symsByFile.keys.filter { !_.exists }
    for (f <- deleted) {
      removeDeleted(f)
    }
  }

  /** Remove symbols defined by file that no longer exist. */
  def removeDeleted(f: AbstractFile) {
    val syms = symsByFile(f)
    unindexTopLevelSyms(syms)
    for (s <- syms) {
      s.owner.info.decls unlink s
    }
    symsByFile.remove(f)
    unitOfFile.remove(f)
  }

  override def syncTopLevelSyms(unit: RichCompilationUnit) {
    super.syncTopLevelSyms(unit)
    unindexTopLevelSyms(deletedTopLevelSyms)
    indexTopLevelSyms(newTopLevelSyms)
    //    WARNING: Clearing the set here makes 
    //    recentlyDeleted useless.
    deletedTopLevelSyms.clear()
    newTopLevelSyms.clear()
  }

  private def typePublicMembers(tpe: Type): Iterable[TypeMember] = {
    val members = new mutable.LinkedHashMap[Symbol, TypeMember]
    def addTypeMember(sym: Symbol, pre: Type, inherited: Boolean, viaView: Symbol) {
      try {
        val m = new TypeMember(
          sym,
          sym.tpe,
          sym.isPublic,
          inherited,
          viaView)
        members(sym) = m
      } catch {
        case e =>
        System.err.println("Error: Omitting member " + sym + ": " + e)
      }
    }
    for (sym <- tpe.decls) {
      addTypeMember(sym, tpe, false, NoSymbol)
    }
    for (sym <- tpe.members) {
      addTypeMember(sym, tpe, true, NoSymbol)
    }
    members.values
  }

  protected def getMembersForTypeAt(p: Position): Iterable[Member] = {
    typeAt(p) match {
      case Some(tpe) => {
        if (isNoParamArrowType(tpe)) {
          typePublicMembers(typeOrArrowTypeResult(tpe))
        } else {
          val members: Iterable[Member] = try {
            wrapTypeMembers(p)
          } catch {
            case e => {
              System.err.println("Error retrieving type members:")
              e.printStackTrace(System.err)
              List()
            }
          }
          // Remove duplicates
          // Filter out synthetic things
          val bySym = new mutable.LinkedHashMap[Symbol, Member]
          for (m <- (members ++ typePublicMembers(tpe))) {
            if (!m.sym.nameString.contains("$")) {
              bySym(m.sym) = m
            }
          }
          bySym.values
        }
      }
      case None => {
        System.err.println("ERROR: Failed to get any type information :(  ")
          List()
	}
      }
    }

    protected def inspectType(tpe: Type): TypeInspectInfo = {
      new TypeInspectInfo(
	TypeInfo(tpe),
	companionTypeOf(tpe).map(cacheType),
	prepareSortedInterfaceInfo(typePublicMembers(tpe.asInstanceOf[Type])))
    }

    protected def inspectTypeAt(p: Position): Option[TypeInspectInfo] = {
      val members = getMembersForTypeAt(p)
      val preparedMembers = prepareSortedInterfaceInfo(members)
      typeAt(p).map{ t =>
	new TypeInspectInfo(
          TypeInfo(t),
          companionTypeOf(t).map(cacheType),
          preparedMembers)
      }
    }

    private def typeOfTree(t: Tree): Option[Type] = {
      var tree = t
      tree = tree match {
	case Select(qual, name) if tree.tpe == ErrorType => {
          qual
	}
	case t: ImplDef if t.impl != null => {
          t.impl
	}
	case t: ValOrDefDef if t.tpt != null => {
          t.tpt
	}
	case t: ValOrDefDef if t.rhs != null => {
          t.rhs
	}
	case t => t
      }
      if (tree.tpe != null) {
	Some(tree.tpe)
      } else {
	None
      }
    }

    protected def typeAt(p: Position): Option[Type] = {
      val tree = wrapTypedTreeAt(p)
      typeOfTree(tree)
    }

    protected def typeByName(name: String): Option[Type] = {
      def maybeType(sym: Symbol) = sym match {
	case NoSymbol => None
	case sym: Symbol => Some(sym.tpe)
	case _ => None
      }
      try {
	if (name.endsWith("$")) {
          maybeType(definitions.getModule(name.substring(0, name.length - 1)))
	} else {
          maybeType(definitions.getClass(name))
	}
      } catch {
	case e => None
      }
    }

    protected def filterMembersByPrefix(members:List[Member], prefix: String, 
      matchEntire: Boolean, caseSens: Boolean):List[Member] = members.filter{ m =>
      val prefixUpper = prefix.toUpperCase()
      val sym = m.sym
      val ns = sym.nameString
      (((matchEntire && ns == prefix) ||
	  (!matchEntire && caseSens && ns.startsWith(prefix)) ||
	  (!matchEntire && !caseSens && ns.toUpperCase().startsWith(prefixUpper)))
	&& !sym.nameString.contains("$"))
    }

    protected def symbolAt(p: Position): Option[Symbol] = {
      p.source.file
      val tree = wrapTypedTreeAt(p)
      if (tree.symbol != null) {
	Some(tree.symbol)
      } else {
	None
      }
    }

    // TODO: 
    // This hides the core implementation is Contexts.scala, which
    // has been patched. Once this bug is fixed, we can get rid of 
    // this workaround.
    private def transformImport(selectors: List[ImportSelector], sym: Symbol): List[Symbol] = selectors match {
      case List() => List()
      case List(ImportSelector(nme.WILDCARD, _, _, _)) => List(sym)
      case ImportSelector(from, _, to, _) :: _ if (from.toString == sym.name.toString) =>
      if (to == nme.WILDCARD) List()
      else { val sym1 = sym.cloneSymbol; sym1.name = to; List(sym1) }
      case _ :: rest => transformImport(rest, sym)
    }

    protected def usesOfSymbolAtPoint(p: Position): Iterable[RangePosition] = {
      symbolAt(p) match {
	case Some(s) => {
	  val gi = new GlobalIndexes {
            val global = RichPresentationCompiler.this
            val sym = s.asInstanceOf[global.Symbol]
            val cuIndexes = this.global.unitOfFile.values.map { u =>
              CompilationUnitIndex(u.body)
            }
            val index = GlobalIndex(cuIndexes.toList)
            val result = index.occurences(sym).map {
              _.pos match {
		case p: RangePosition => p
		case p =>
		new RangePosition(
		  p.source, p.point, p.point, p.point)
              }
            }
	  }
	  gi.result
	}
	case None => List()
      }
    }

    private var notifyWhenReady = false

    override def isOutOfDate(): Boolean = {
      if (notifyWhenReady && !super.isOutOfDate) {
	parent ! FullTypeCheckCompleteEvent()
	notifyWhenReady = false
      }
      super.isOutOfDate
    }

    protected def setNotifyWhenReady() {
      notifyWhenReady = true
    }

    protected def reloadAndTypeFiles(sources: Iterable[SourceFile]) = {
      wrapReloadSources(sources.toList)
      sources.foreach { s =>
	wrapTypedTree(s, true)
      }
    }

    override def askShutdown() {
      super.askShutdown()
      parent = null
      indexer = null
    }

    override def finalize() {
      System.out.println("Finalizing Global instance.")
    }

    /*
    * The following functions wrap up operations that interact with
    * the presentation compiler. The wrapping just helps with the
    * create response / compute / get result pattern.
    *
    * These units of work should probably be wrapped up into a
    * Work monad that will make it easier to compose the operations.
    */

    def wrap[A](compute: Response[A] => Unit, handle: Throwable => A): A = {
      val result = new Response[A]
      compute(result)
      result.get.fold(o => o, handle)
    }

    def wrapReloadPosition(p: Position): Unit =
    wrapReloadSource(p.source)

    def wrapReloadSource(source: SourceFile): Unit =
    wrapReloadSources(List(source))

    def wrapReloadSources(sources: List[SourceFile]): Unit = {
      val superseeded = scheduler.dequeueAll {
	case ri: ReloadItem if ri.sources == sources => Some(ri)
	case _ => None
      }
      superseeded.foreach(_.response.set())
      wrap[Unit](r => new ReloadItem(sources, r).apply(), _ => ())
    }

    def wrapTypeMembers(p: Position): List[Member] =
    wrap[List[Member]](r => new AskTypeCompletionItem(p, r).apply(), _ => List())

    def wrapTypedTree(source: SourceFile, forceReload: Boolean): Tree =
    wrap[Tree](r => new AskTypeItem(source, forceReload, r).apply(), t => throw t)

    def wrapTypedTreeAt(position: Position): Tree =
    wrap[Tree](r => new AskTypeAtItem(position, r).apply(), t => throw t)

  }

