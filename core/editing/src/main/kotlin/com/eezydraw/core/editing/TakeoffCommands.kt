package com.eezydraw.core.editing

import com.eezydraw.core.model.*

object TakeoffCommands {
    fun settings(document:ProjectDocument,value:TakeoffSettings):EditCommand = EditCommands.between(document,
        document.copy(project=document.project.copy(settings=document.project.settings.copy(takeoff=value))),"Schedule settings")

    fun rate(document:ProjectDocument,key:String,amount:Double?,libraryIds:Set<LibraryItemId> = emptySet()):EditCommand {
        require(key.isNotBlank() && (amount==null || amount.isFinite() && amount in 0.0..1e12))
        val settings=document.project.settings.takeoff;val currency=settings.currency
        val currencies=settings.rates[key].orEmpty().let {if(amount==null)it-currency else it+(currency to amount)}
        val rates=if(currencies.isEmpty())settings.rates-key else settings.rates+(key to currencies)
        val definitions=document.libraryItems.map {item->if(item.id !in libraryIds)item else item.copy(unitRates=if(amount==null)item.unitRates-currency else item.unitRates+(currency to amount))}
        return EditCommands.between(document,document.copy(libraryItems=definitions,project=document.project.copy(settings=document.project.settings.copy(takeoff=settings.copy(rates=rates)))),"BOQ unit rate")
    }
}
