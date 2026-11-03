package com.fadysaeed.pdfx.data

/** A topic a file can be about, the words that say so, and the broad area it belongs to. */
class Concept(val name: String, val area: String, val terms: List<String>)

/**
 * The topics a psychiatrist's files are about, each with the words and drug names that mark it, in
 * broad areas (Mood, Psychosis, Medicines…), plus the paperwork of a life (money, housing, travel,
 * registration). A file is matched to a topic by these words anywhere in it, so "manic", "mania",
 * "lithium" and "hypomania" all count for Bipolar, and a paper about lamotrigine in bipolar
 * depression lands under Bipolar, Depression and Mood stabilisers at once.
 */
object Concepts {
    private fun c(area: String, name: String, vararg terms: String) = Concept(name, area, listOf(name) + terms)

    val all: List<Concept> = listOf(
        // Mood
        c("Mood disorders", "Depression", "depressive", "major depressive", "MDD", "dysthymia", "anhedonia", "treatment-resistant depression", "PHQ-9", "MADRS", "HAM-D"),
        c("Mood disorders", "Bipolar", "bipolar disorder", "mania", "manic", "hypomania", "hypomanic", "mood episode", "rapid cycling", "YMRS"),
        c("Mood disorders", "Lithium", "lithium carbonate", "lithium level", "serum lithium", "lithium toxicity"),
        c("Mood disorders", "Mood stabilisers", "mood stabiliser", "mood stabilizer", "valproate", "sodium valproate", "lamotrigine", "carbamazepine"),
        c("Mood disorders", "Antidepressants", "antidepressant", "SSRI", "SNRI", "sertraline", "escitalopram", "citalopram", "fluoxetine", "paroxetine", "venlafaxine", "desvenlafaxine", "duloxetine", "mirtazapine", "agomelatine", "vortioxetine", "bupropion", "tricyclic", "MAOI"),
        c("Mood disorders", "Perinatal mental health", "perinatal", "postnatal", "postpartum", "antenatal", "puerperal", "pregnancy", "breastfeeding", "lactation"),
        c("Mood disorders", "ECT and brain stimulation", "ECT", "electroconvulsive", "TMS", "transcranial magnetic", "ketamine", "esketamine", "neuromodulation"),
        // Psychosis
        c("Psychosis", "Schizophrenia", "schizophrenic", "schizoaffective", "first episode psychosis", "negative symptoms", "PANSS"),
        c("Psychosis", "Psychosis", "psychotic", "delusion", "delusions", "hallucination", "hallucinations", "paranoia", "early psychosis"),
        c("Psychosis", "Clozapine", "clozapine", "treatment-resistant schizophrenia", "neutropenia", "myocarditis", "clozapine level"),
        c("Psychosis", "Antipsychotics", "antipsychotic", "olanzapine", "risperidone", "paliperidone", "aripiprazole", "brexpiprazole", "quetiapine", "haloperidol", "lurasidone", "amisulpride", "ziprasidone", "cariprazine", "chlorpromazine", "zuclopenthixol"),
        c("Psychosis", "Long-acting injectables", "long-acting injectable", "LAI", "depot", "depot antipsychotic", "paliperidone palmitate", "aripiprazole monohydrate"),
        c("Psychosis", "Movement side effects", "tardive dyskinesia", "akathisia", "extrapyramidal", "parkinsonism", "dystonia", "neuroleptic malignant syndrome"),
        // Anxiety and trauma
        c("Anxiety and trauma", "Anxiety", "anxiety disorder", "generalised anxiety", "generalized anxiety", "GAD-7", "panic disorder", "panic attacks", "social anxiety", "phobia", "agoraphobia"),
        c("Anxiety and trauma", "PTSD and trauma", "PTSD", "post-traumatic", "posttraumatic", "complex trauma", "trauma-informed", "childhood trauma", "dissociation", "dissociative"),
        c("Anxiety and trauma", "OCD", "obsessive-compulsive", "obsessive compulsive", "compulsions", "hoarding", "body dysmorphic"),
        // Personality
        c("Personality", "Borderline personality disorder", "borderline personality", "BPD", "emotionally unstable personality", "good psychiatric management", "GPM", "mentalization"),
        c("Personality", "Personality disorders", "personality disorder", "antisocial personality", "narcissistic", "psychopathy"),
        // Neurodevelopment
        c("Neurodevelopment", "ADHD", "attention-deficit", "attention deficit", "hyperactivity", "methylphenidate", "lisdexamfetamine", "dexamfetamine", "dexamphetamine", "atomoxetine", "guanfacine", "stimulant medication"),
        c("Neurodevelopment", "Autism", "autistic", "autism spectrum", "ASD", "Asperger"),
        c("Neurodevelopment", "Intellectual disability", "intellectual disability", "learning disability", "developmental delay"),
        c("Neurodevelopment", "Tourette and tics", "Tourette", "tic disorder", "tics"),
        // Substances
        c("Substance use", "Alcohol", "alcohol use disorder", "alcohol withdrawal", "Wernicke", "thiamine", "naltrexone", "acamprosate", "disulfiram"),
        c("Substance use", "Methamphetamine", "methamphetamine", "amphetamine", "stimulant use", "psychostimulant"),
        c("Substance use", "Opioids", "opioid", "opioids", "heroin", "methadone", "buprenorphine", "opioid substitution", "naloxone"),
        c("Substance use", "Cannabis", "cannabis", "cannabinoid", "THC", "CBD", "marijuana", "synthetic cannabinoids"),
        c("Substance use", "Addiction", "substance use disorder", "addiction", "harm reduction", "gambling"),
        // Risk and acute care
        c("Risk and acute care", "Suicide and self-harm", "suicide", "suicidal", "self-harm", "self harm", "deliberate self-harm", "overdose", "safety plan", "suicide risk"),
        c("Risk and acute care", "Aggression and agitation", "aggression", "agitation", "violence", "acute behavioural disturbance", "rapid tranquillisation", "de-escalation", "seclusion", "restraint"),
        c("Risk and acute care", "Delirium", "delirium", "acute confusion", "confusional state"),
        c("Risk and acute care", "Catatonia", "catatonia", "catatonic", "lorazepam challenge", "Bush-Francis"),
        c("Risk and acute care", "Emergency psychiatry", "emergency department", "crisis team", "crisis resolution", "acute inpatient", "inpatient unit"),
        // Older and younger people
        c("Life stages", "Dementia", "dementia", "Alzheimer", "cognitive impairment", "MCI", "BPSD", "behavioural and psychological symptoms", "cholinesterase", "donepezil", "memantine"),
        c("Life stages", "Old age psychiatry", "older people", "older adults", "elderly", "late-life", "psychogeriatric", "old age"),
        c("Life stages", "Child and adolescent", "child and adolescent", "adolescent", "adolescents", "children", "youth", "CAMHS", "paediatric", "pediatric"),
        // Eating and body
        c("Eating and physical health", "Eating disorders", "eating disorder", "anorexia nervosa", "bulimia", "binge eating", "ARFID", "refeeding"),
        c("Eating and physical health", "Metabolic health", "metabolic syndrome", "weight gain", "diabetes", "dyslipidaemia", "obesity", "metformin", "GLP-1", "semaglutide"),
        c("Eating and physical health", "Cardiac safety", "QTc", "QT prolongation", "ECG", "cardiac", "arrhythmia", "torsades"),
        c("Eating and physical health", "Sleep", "insomnia", "sleep disorder", "melatonin", "hypnotic", "zopiclone", "obstructive sleep apnoea", "circadian"),
        c("Eating and physical health", "Neuropsychiatry", "epilepsy", "traumatic brain injury", "functional neurological", "conversion disorder", "encephalitis", "neuropsychiatric"),
        // Medicines in general
        c("Medicines", "Psychopharmacology", "pharmacokinetics", "pharmacodynamics", "drug interaction", "CYP450", "cytochrome", "therapeutic drug monitoring", "receptor"),
        c("Medicines", "Benzodiazepines", "benzodiazepine", "diazepam", "lorazepam", "clonazepam", "alprazolam", "oxazepam", "temazepam"),
        c("Medicines", "Serotonin syndrome", "serotonin syndrome", "serotonin toxicity"),
        // Therapies
        c("Therapies", "CBT", "cognitive behavioural therapy", "cognitive behavioral therapy", "cognitive therapy", "exposure therapy"),
        c("Therapies", "DBT", "dialectical behaviour therapy", "dialectical behavior therapy"),
        c("Therapies", "Psychotherapy", "psychotherapy", "psychodynamic", "psychoanalytic", "interpersonal therapy", "schema therapy", "acceptance and commitment", "mindfulness", "family therapy"),
        // Law, ethics, service
        c("Law and ethics", "Mental Health Act", "Mental Health Act", "compulsory treatment", "involuntary", "community treatment order", "CTO", "Mental Health (Compulsory Assessment and Treatment) Act", "tribunal"),
        c("Law and ethics", "Capacity and consent", "capacity", "decision-making capacity", "informed consent", "guardianship", "enduring power of attorney", "advance directive"),
        c("Law and ethics", "Forensic psychiatry", "forensic", "court report", "fitness to stand trial", "prison", "offender", "insanity"),
        c("Law and ethics", "Ethics and complaints", "ethics", "ethical", "boundary", "complaint", "Health and Disability Commissioner", "HDC", "Medical Council", "AHPRA", "MCNZ"),
        c("Work and training", "Supervision and teaching", "supervision", "supervisor", "registrar", "trainee", "teaching", "curriculum", "RANZCP", "exam", "OSCE", "CPD"),
        c("Work and training", "Services and policy", "model of care", "service delivery", "workforce", "policy", "Te Whatu Ora", "Health New Zealand", "funding", "audit"),
        // A life's paperwork
        c("Life admin", "Money and tax", "invoice", "tax invoice", "receipt", "tax return", "IRD", "ATO", "payslip", "bank statement", "superannuation", "KiwiSaver"),
        c("Life admin", "Home and property", "tenancy", "lease", "rental", "mortgage", "sale and purchase", "LIM report", "building inspection", "real estate"),
        c("Life admin", "Travel and visas", "visa", "passport", "itinerary", "boarding pass", "flight", "booking confirmation", "immigration"),
        c("Life admin", "Insurance", "insurance", "policy schedule", "insurance claim", "indemnity"),
        c("Life admin", "Employment", "employment agreement", "employment contract", "offer letter", "job description", "locum", "credentialing", "scope of practice"),
        c("Life admin", "School and children", "school enrolment", "enrolment", "report card", "kindergarten", "childcare"),
    )
}
