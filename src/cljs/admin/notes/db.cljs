(ns admin.notes.db)


(def path ::notes)


(def default-value
  {path {:creating         false
         :form             {:notify true}
         :notes            []
         :editing-notes    {}
         :commenting-notes {}
         :notes-pagination {:size 5
                            :page 1}}})
