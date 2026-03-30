package com.aria.assistant

object MoodDetector {
    
    enum class Mood {
        HAPPY, SAD, ANGRY, TIRED, EXCITED, WORRIED, NEUTRAL
    }
    
    private val happyKeywords = listOf(
        "happy", "good", "great", "awesome", "excellent", "wonderful", "amazing", "love",
        "khushi", "bhalo", "bhalo lagche", "moja", "perfect", "best", "excited"
    )
    
    private val sadKeywords = listOf(
        "sad", "unhappy", "depressed", "down", "upset", "cry", "hurt", "alone",
        "kharap", "kharap lagche", "dukho", "mon kharap", "miss", "lonely"
    )
    
    private val angryKeywords = listOf(
        "angry", "mad", "furious", "hate", "annoyed", "irritated", "frustrated",
        "rage", "rege gachi", "chira", "gussa"
    )
    
    private val tiredKeywords = listOf(
        "tired", "exhausted", "sleepy", "fatigue", "worn out", "drained",
        "thaka", "tired lagche", "ghum", "ghumiye porechi", "rest"
    )
    
    private val excitedKeywords = listOf(
        "excited", "yay", "wow", "omg", "can't wait", "thrilled",
        "darun", "mast", "ecited", "khub bhalo"
    )
    
    private val worriedKeywords = listOf(
        "worried", "anxious", "nervous", "scared", "afraid", "tension",
        "chinta", "tension", "bhoy", "worried hoye gachi"
    )
    
    fun detectMood(text: String): Mood {
        val lowerText = text.lowercase()
        
        // Check for emojis first
        when {
            lowerText.contains("😢") || lowerText.contains("😭") || lowerText.contains("😔") -> return Mood.SAD
            lowerText.contains("😊") || lowerText.contains("😃") || lowerText.contains("😄") || lowerText.contains("🥰") -> return Mood.HAPPY
            lowerText.contains("😡") || lowerText.contains("😠") || lowerText.contains("🤬") -> return Mood.ANGRY
            lowerText.contains("😴") || lowerText.contains("😪") || lowerText.contains("🥱") -> return Mood.TIRED
            lowerText.contains("🎉") || lowerText.contains("🎊") || lowerText.contains("😍") -> return Mood.EXCITED
        }
        
        // Check keywords
        var happyScore = 0
        var sadScore = 0
        var angryScore = 0
        var tiredScore = 0
        var excitedScore = 0
        var worriedScore = 0
        
        happyKeywords.forEach { if (lowerText.contains(it)) happyScore++ }
        sadKeywords.forEach { if (lowerText.contains(it)) sadScore++ }
        angryKeywords.forEach { if (lowerText.contains(it)) angryScore++ }
        tiredKeywords.forEach { if (lowerText.contains(it)) tiredScore++ }
        excitedKeywords.forEach { if (lowerText.contains(it)) excitedScore++ }
        worriedKeywords.forEach { if (lowerText.contains(it)) worriedScore++ }
        
        // Return highest scoring mood
        val scores = mapOf(
            Mood.HAPPY to happyScore,
            Mood.SAD to sadScore,
            Mood.ANGRY to angryScore,
            Mood.TIRED to tiredScore,
            Mood.EXCITED to excitedScore,
            Mood.WORRIED to worriedScore
        )
        
        val maxScore = scores.maxByOrNull { it.value }
        return if (maxScore != null && maxScore.value > 0) {
            maxScore.key
        } else {
            Mood.NEUTRAL
        }
    }
    
    fun getMoodPrompt(mood: Mood): String {
        return when(mood) {
            Mood.HAPPY -> "\n\nUser seems HAPPY. Be cheerful, celebrate with them, share their joy!"
            Mood.SAD -> "\n\nUser seems SAD. Be extra caring, comforting, empathetic. Offer support and listen."
            Mood.ANGRY -> "\n\nUser seems ANGRY. Be calm, understanding, help them cool down. Don't argue."
            Mood.TIRED -> "\n\nUser seems TIRED. Be gentle, suggest rest, offer comfort. Don't demand much."
            Mood.EXCITED -> "\n\nUser seems EXCITED. Match their energy! Be enthusiastic and supportive!"
            Mood.WORRIED -> "\n\nUser seems WORRIED. Be reassuring, calm, supportive. Help them feel better."
            Mood.NEUTRAL -> ""
        }
    }
}
