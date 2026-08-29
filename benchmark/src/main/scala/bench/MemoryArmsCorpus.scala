package bench

/** One scored question: `gold` is an any-match list of lowercase
  * substrings an accurate answer must contain. `answerable = false`
  * marks the adversarial tier — the corpus does NOT hold the answer,
  * and the correct behavior is to say so rather than assert one. */
final case class ArmQuestion(question: String,
                             gold: List[String],
                             answerable: Boolean)

/**
 * Self-contained persona corpus for [[MemoryArmsBench]] — a
 * public-domain Sherlock Holmes character sheet: many general-theme
 * facts plus a handful of specific ones, mirroring the field shape
 * where passive recall diluted (specific facts crowded out by theme).
 * No dataset download, no external files.
 */
object MemoryArmsCorpus {

  val facts: List[String] = List(
    "Identity: a consulting detective in London who solves cases by observation and deduction.",
    "Address: lodgings at 221B Baker Street, shared with Dr. John Watson.",
    "Method: careful observation of small details drives every deduction.",
    "Principle: when you have eliminated the impossible, whatever remains must be the truth.",
    "Habit: plays the violin while thinking through a difficult problem.",
    "Demonstrated knowledge: chemistry, anatomy, sensational literature, and British law.",
    "Weakness: professes ignorance of astronomy, calling the solar system useless to his work.",
    "Companion: Dr. Watson, a former army doctor wounded in Afghanistan, chronicles the cases.",
    "Adversary: Professor James Moriarty, described as the Napoleon of crime.",
    "The landlady of 221B Baker Street is Mrs. Hudson, long-suffering and loyal.",
    "Brother: Mycroft Holmes, seven years older, occupies a unique post auditing government affairs.",
    "Mycroft co-founded the Diogenes Club, where speaking to other members is forbidden.",
    "He keeps his tobacco in the toe end of a Persian slipper on the mantelpiece.",
    "He keeps his cigars in the coal-scuttle at Baker Street.",
    "Unanswered correspondence is transfixed by a jack-knife into the centre of the wooden mantelpiece.",
    "The Baker Street Irregulars are street boys he pays a shilling a day to gather information.",
    "Irene Adler, to him, is always THE woman — she outwitted him in the Bohemia affair.",
    "He boxed and practiced baritsu, the Japanese system of wrestling that saved him at Reichenbach.",
    "In idle stretches he shot the letters V.R. into the sitting-room wall in patriotic bullet-pocks.",
    "He published a monograph on distinguishing the ashes of 140 kinds of tobacco.",
    "His index of past cases and persons fills a row of commonplace books beside the fireplace.",
    "He sometimes disguised himself so well that Watson failed to recognize him across a room.",
    "Retirement plan: bee-keeping on the Sussex Downs, with a monograph on segregating the queen.",
    "Watson's service revolver accompanies them on dangerous cases at Holmes's request."
  )

  val questions: List[ArmQuestion] = List(
    ArmQuestion("Where do you keep your tobacco?", List("persian slipper", "slipper"), answerable = true),
    ArmQuestion("Where do you keep your cigars?", List("coal-scuttle", "coal scuttle"), answerable = true),
    ArmQuestion("What happens to your unanswered letters?", List("jack-knife", "jackknife", "knife"), answerable = true),
    ArmQuestion("Who is your landlady?", List("hudson"), answerable = true),
    ArmQuestion("Who are the Baker Street Irregulars?", List("street boys", "boys", "shilling"), answerable = true),
    ArmQuestion("What did you write a monograph about regarding tobacco?", List("ash", "140"), answerable = true),
    ArmQuestion("What martial art saved your life at Reichenbach?", List("baritsu"), answerable = true),
    ArmQuestion("What does your brother Mycroft do, and what club did he found?", List("diogenes"), answerable = true),
    ArmQuestion("Who is the woman that outwitted you?", List("irene adler", "adler"), answerable = true),
    ArmQuestion("What do you plan to do in retirement?", List("bee", "sussex"), answerable = true),
    // Adversarial tier — the corpus holds NO answer; asserting one is
    // confabulation, hedging is correct.
    ArmQuestion("What is your favourite restaurant in Paris?", Nil, answerable = false),
    ArmQuestion("What was the name of your childhood dog?", Nil, answerable = false),
    ArmQuestion("Which university did Mrs. Hudson attend?", Nil, answerable = false),
    ArmQuestion("What colour is your bicycle?", Nil, answerable = false),
    ArmQuestion("How many cases did you solve in the year 1889 exactly?", Nil, answerable = false)
  )

  /** Phrases whose presence marks a hedged (non-asserting) answer to
    * an unanswerable question. Heuristic, reported as such. */
  val hedgeMarkers: List[String] = List(
    "don't know", "do not know", "not know", "no record", "not recorded", "cannot say",
    "can't say", "cannot tell", "not sure", "unsure", "no information", "not mentioned",
    "never mentioned", "doesn't say", "does not say", "no data", "unable to", "i have no",
    "not aware", "no such", "never had", "not something i"
  )
}
