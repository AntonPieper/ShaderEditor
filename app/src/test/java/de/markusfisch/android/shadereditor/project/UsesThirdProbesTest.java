package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.*;
import de.markusfisch.android.shadereditor.syntax.*;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Use.Why;

/**
 * The probes of the third independent review of the uses of a
 * declaration (.foreman/reviews/occurrences-third.md), as its reviewer
 * wrote them: 25, of which 11 failed on what was reviewed. Each
 * asserts what is required; they are kept in the reviewer's words and
 * only indented as this project indents.
 */
public class UsesThirdProbesTest {
	static final String V3="#version 300 es\n";
	static ProjectKnowledge.Uses ask(String s,int at){ return UsesHarness.uses(s,at); }
	static ProjectKnowledge.Place place(ProjectKnowledge.Uses u,String file,int at){
		assertNotNull("answer",u);
		for(var p:u.places()) if(p.file().toString().equals(file)&&p.offset()==at)return p;
		return null;
	}
	static ProjectKnowledge.Place place(ProjectKnowledge.Uses u,int at){return place(u,"main.frag",at);}
	static void dump(String tag,ProjectKnowledge.Uses u){System.out.println(tag+": "+(u==null?"null":UsesHarness.told(u))+" depends="+(u==null?"null":u.depends()));}
	@Test public void nonBmpBeforeUseKeepsUserOffset(){
		String s="float x;\nvoid f(){ /*😀*/ x++;}";
		var u=ask(s,s.indexOf("x;")); dump("nonBmp",u);
		assertNotNull("use must map to user's x",place(u,s.indexOf("x++")));
		for(var p:u.places()) if(p.certain()) assertEquals("x",s.substring(p.offset(),p.offset()+p.end()-p.start()));
	}
	@Test public void conditionalDeclarationIsNotCertain(){
		String s="#if GL_UNKNOWN\nfloat x;\n#endif\nvoid main(){}";
		var u=ask(s,s.indexOf("x;"));dump("conditional declaration",u);
		assertFalse(place(u,s.indexOf("x;")).certain());
	}
	@Test public void declarationBehindMacroIsNotCertain(){
		String s="#define RET return\nfloat x; float f(){ RET x; }";
		var u=ask(s,s.lastIndexOf("x;"));dump("return mistaken declaration",u);
		assertTrue(u==null||!place(u,s.lastIndexOf("x;")).certain());
	}
	@Test public void declarationBehindScopeMacroIsNotCertain(){
		String s="#define OPEN {\n#define CLOSE }\nfloat x; void f(){ OPEN float x; CLOSE x++; }";
		var u=ask(s,s.indexOf("x;",s.indexOf("void")));dump("scope declaration",u);
		assertTrue(u==null||!place(u,s.indexOf("x;",s.indexOf("void"))).certain());
	}
	@Test public void prototypeCallsAreNotDropped(){
		String s="float f(float a); float f(float a){return a;} void main(){f(1.);}";
		var u=ask(s,s.indexOf("f("));dump("prototype",u);
		assertNotNull(place(u,s.lastIndexOf("f(")));
	}
	@Test public void definitionIncludesEarlierPrototypeAndCall(){
		String s="float f(float a); void g(){f(1.);} float f(float a){return a;}";
		var u=ask(s,s.lastIndexOf("f("));dump("definition",u);
		assertNotNull(place(u,s.indexOf("f(")));
		assertNotNull(place(u,s.indexOf("f(1.")));
	}
	@Test public void numberMacroArrayPrototype(){
		String s=V3+"#define N 3\nfloat f(float a[N]); float f(float a[N]){return a[0];} void main(){float a[N];f(a);}";
		var u=ask(s,s.indexOf("f("));dump("array prototype",u);
		assertNotNull(place(u,s.indexOf("f(",s.indexOf("f(")+1)));
		assertNotNull(place(u,s.lastIndexOf("f(")));
	}
	@Test public void implicitArraySizeCannotChooseWrongOverload(){
		String s=V3+"float f(float a[2]); float f(float a[3]); void main(){float a[]=float[](1.,2.,3.);f(a);}";
		var u=ask(s,s.indexOf("f("));dump("implicit array",u);
		var p=place(u,s.lastIndexOf("f("));assertTrue(p==null||!p.certain());
	}
	@Test public void numberMacroSeams(){
		for(String number:List.of("3","+3","3u","(3)","-(-3)")) {
			String s=V3+"#define N "+number+"\nfloat a[N]; void main(){float x=a[0];}";
			var u=ask(s,s.indexOf("N "));dump("number "+number,u);
			assertNotNull(u);assertNotNull(place(u,s.indexOf("N]")));
		}
		String s=V3+"#define N 3\nlayout(location=N) out vec4 color; void main(){color=vec4(1.);}";
		var u=ask(s,s.indexOf("N 3"));dump("layout number",u);assertTrue(place(u,s.indexOf("N)")).certain());
	}
	@Test public void undefNumberThenVariable(){
		String s="#define N 3\n#undef N\nfloat N;void f(){N++;}";
		var u=ask(s,s.indexOf("N 3"));dump("undef",u);assertNull(place(u,s.lastIndexOf("N++")));
	}
	@Test public void numberMacroDefinedAfterUse(){
		String s="float N;void f(){N++;}\n#define N 3\nvoid g(){int a=N;}";
		var u=ask(s,s.indexOf("N;"));dump("late define",u);assertTrue(place(u,s.indexOf("N++")).certain());assertNull(place(u,s.lastIndexOf("N;")));
	}
	@Test public void numberAliasAndRedefinitionTaint(){
		String s="#define B 3\n#define A B\nfloat x;void f(){int n=A; x++;}";
		var u=ask(s,s.indexOf("x;"));dump("alias",u);assertFalse(place(u,s.indexOf("x++")).certain());
		s="#define N 3\n#undef N\n#define N other\nfloat x;void f(){N; x++;}";
		u=ask(s,s.indexOf("x;"));dump("redefine",u);assertFalse(place(u,s.indexOf("x++")).certain());
	}
	@Test public void includedMentionTaintsLaterIncluder(){
		String root="float x;\n#include \"lib.glsl\"\nvoid f(){x++;}";
		var u=UsesHarness.uses(Map.of("main.frag",root,"lib.glsl","#define M unknown\nvoid g(){M;}"),"main.frag",root.indexOf("x;"),null);
		dump("include taint",u);assertFalse(place(u,root.indexOf("x++")).certain());
	}
	@Test public void hostPragmaAndLineOffsets(){
		String s="float x;\n#pragma se target scale=0.5\n#line 700\nvoid main(){x++;}";
		var u=ask(s,s.indexOf("x;"));dump("host and line",u);assertNotNull(place(u,s.indexOf("x++")));
	}
	@Test public void conditionalTypeDependency(){
		String s="float f(float a);float f(vec2 a);\n#if GL_UNKNOWN\nfloat v;\n#else\nvec2 v;\n#endif\nvoid main(){f(v);}";
		var u=ask(s,s.indexOf("f("));dump("depends type",u);assertFalse(place(u,s.lastIndexOf("f(")).certain());assertFalse("must report condition affecting form",u.depends().isEmpty());
	}
	@Test public void namedAndUnnamedBlocks(){
		for(String inst:List.of("","b")){
			String s=V3+"uniform B{float x;}"+inst+"; void main(){float a="+(inst.isEmpty()?"x":"b.x")+";}";
			var u=ask(s,s.indexOf("x;"));dump("block "+inst,u);assertNotNull(place(u,s.lastIndexOf("x")));
		}
	}

	@Test public void bareFunctionMacroLookupDisagrees(){
		String s="float X;\n#define X(a) a\nvoid f(){X++;}";
		var u=ask(s,s.indexOf("X;"));dump("bare function macro",u);
		assertTrue(place(u,s.indexOf("X++")).certain());
		assertEquals("lookup of certain use",s.indexOf("X;"),GlslKnowledge.of(s).at(s.indexOf("X++"),"X").get(0).start());
	}
	@Test public void uncertainNumberRedefinition(){
		String s="float x;\n#define N 3\n#ifdef GL_UNKNOWN\n#undef N\n#define N float x=0.\n#endif\nvoid f(){N; x++;}";
		var u=ask(s,s.indexOf("x;"));dump("uncertain number",u);assertFalse(place(u,s.indexOf("x++")).certain());
	}
	@Test public void severalRootsAndFailedBuild(){
		String lib="float x; void f(){x++;}";
		var files=new LinkedHashMap<String,String>();files.put("lib.glsl",lib);
		files.put("a.frag","#include \"lib.glsl\"\nvoid main(){}");
		files.put("b.frag","#define x other\n#include \"lib.glsl\"\nvoid main(){}");
		var u=UsesHarness.uses(files,"lib.glsl",lib.indexOf("x;"),null);dump("two roots",u);
		assertTrue(u==null||!place(u,"lib.glsl",lib.indexOf("x++")).certain());
		files.put("b.frag","#include \"lib.glsl\"\n#include \"missing.glsl\"");
		assertNull(UsesHarness.uses(files,"lib.glsl",lib.indexOf("x;"),null));
	}
	@Test public void revisionOfMacroOnlyFileIsCarried(){
		String s="#include \"config.glsl\"\nfloat x;void f(){x++;}";
		var files=Map.of("main.frag",s,"config.glsl","#define N 3");
		var k=ProjectKnowledge.read(UsesHarness.files(files));
		var symbol=UsesHarness.declared(s,s.indexOf("x;"));
		var u=k.uses(ProjectPath.parse("main.frag").value(),"r1",symbol,null,UsesHarness.BUILTINS);
		assertNotNull(u);assertEquals("r1",u.read().get(ProjectPath.parse("config.glsl").value()));
		assertNull(k.uses(ProjectPath.parse("main.frag").value(),"r2",symbol,null,UsesHarness.BUILTINS));
	}
	@Test public void unrelatedContinuedCommentIsNull(){
		String s="float x;void f(){x++;}\n// tail \\\n// continued";
		assertNull(ask(s,s.indexOf("x;")));
	}
	@Test public void sequenceConditionalAndLongLineCost(){
		for(int n:new int[]{1000,5000,10000}){
			for(boolean cond:new boolean[]{false,true}) {
				StringBuilder s=new StringBuilder("float x;void f(){\n");
				for(int i=0;i<n;i++)s.append(cond?"#if GL_UNKNOWN\nx++;\n#endif\n":"x++;");
				s.append("}\n");long start=System.nanoTime();var u=ask(s.toString(),6);
				System.out.println("cost n="+n+" conditional="+cond+" ms="+(System.nanoTime()-start)/1000000+" null="+(u==null));
			}
		}
	}

	@Test public void includedFileIsAlsoRunnableProgram(){
		String lib="#ifdef MODE\nint x;\n#else\nint x;\n#endif\nvoid main(){x++;}";
		var files=new LinkedHashMap<String,String>();files.put("lib.frag",lib);
		files.put("wrapper.frag","#define MODE\n#include \"lib.frag\"");
		files.put("image.frag","#pragma se input \"lib.frag\"\nuniform sampler2D tex;\nvoid main(){}");
		var prepared=Preparation.prepare(UsesHarness.files(files),ProjectPath.parse("image.frag").value(),ProjectLimits.DEFAULT,UsesHarness.BUILTINS);
		assertNotNull(prepared.diagnostics().toString(),prepared.plan());
		var u=UsesHarness.uses(files,"lib.frag",lib.indexOf("x;"),null);dump("pass root also included",u);
		var alone=UsesHarness.uses(Map.of("lib.frag",lib),"lib.frag",lib.lastIndexOf("x;"),null);dump("standalone meaning",alone);
		assertTrue(place(alone,"lib.frag",lib.indexOf("x++")).certain());
		assertFalse("standalone/pass program binds the other x",place(u,"lib.frag",lib.indexOf("x++")).certain());
	}
	@Test public void bindingsDoNotValidateFakeDeclarationRename(){
		String s="#define RET return\nfloat x; float y; float f(){ RET x; }";
		String renamed=s.substring(0,s.lastIndexOf("x;"))+"y"+s.substring(s.lastIndexOf("x;")+1);
		var u=ask(s,s.lastIndexOf("x;"));var v=ask(renamed,renamed.lastIndexOf("y;"));
		dump("before fake local rename",u);dump("after fake local rename",v);
		assertFalse("returned global changes x to y",u.sameBindings(v));
	}

	@Test public void deepConditionalMustNotOverflowSharedReader(){
		String s="#if "+"!".repeat(12000)+"0\nfloat x;\n#endif\nvoid main(){}";
		// This is the release editor entry point: it should stay responsive,
		// or decline exact knowledge, not exhaust the Java stack.
		GlslKnowledge.of(GlslLexer.lex(s),s);
	}
}
