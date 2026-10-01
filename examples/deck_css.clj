(ns deck-css
  "Slide typography. A deck lives or dies on type size and restraint, so this
   is the whole design.")

(def css "
.slide            { background-image: linear-gradient(160deg, #10131a 0%, #1b2030 100%); }
.slide .heading   { font-size: 46px; font-weight: 300; color: #ffffff; }
.slide .sub       { font-size: 20px; color: alpha(#ffffff, 0.60); }
.slide .bullet    { font-size: 26px; font-weight: 300; color: alpha(#ffffff, 0.92); }
.slide .prose     { font-size: 24px; font-weight: 300; color: alpha(#ffffff, 0.88); }
.slide .quote     { font-size: 34px; font-weight: 200; font-style: italic;
                    color: #ffffff; }
.slide .dot       { font-size: 26px; color: #4a9eff; }

/* a title slide gets its own, warmer ground so the deck has some rhythm */
.slide.title      { background-image: linear-gradient(160deg, #17203a 0%, #3a2247 60%, #4a2440 100%); }
.slide.title .heading { font-size: 68px; font-weight: 200; }

.chrome           { font-size: 12px; color: alpha(#ffffff, 0.45); padding: 6px 12px; }
.chrome .counter  { font-weight: 700; }
")
